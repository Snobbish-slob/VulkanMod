package net.vulkanmod.vulkan;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.util.VUtil;
import net.vulkanmod.vulkan.util.VkResult;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static net.vulkanmod.vulkan.Vulkan.*;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Owns the per-frame command buffers and fences.
 * <p>
 * Since Minecraft 26.3 the game drives the frame through the GPU backend API: commands are
 * recorded lazily into the current frame's command buffer, render passes are explicit, and the
 * frame ends when the game calls {@code CommandEncoder.submit()}. Presentation is handled by
 * {@link net.vulkanmod.render.engine.VkGpuSurface}, which registers the semaphores the submit
 * has to wait on and signal.
 */
public class Renderer {
    private static Renderer INSTANCE;

    private static VkDevice device;

    public static void initRenderer() {
        INSTANCE = new Renderer();
        INSTANCE.init();
    }

    public static Renderer getInstance() {
        return INSTANCE;
    }

    public static Drawer getDrawer() {
        return INSTANCE.drawer;
    }

    public static int getCurrentFrame() {
        return currentFrame;
    }

    /**
     * Listener for frame lifecycle events, used by resources that keep per-frame state.
     */
    public interface FrameListener {
        void onBeginFrame(int frame);

        default void onFramesNumChanged(int framesNum) {
        }
    }

    private final Set<Pipeline> usedPipelines = new ObjectOpenHashSet<>();
    private Pipeline boundPipeline;
    private long boundPipelineHandle;

    private Drawer drawer;

    private int framesNum;
    private List<VkCommandBuffer> mainCommandBuffers;
    private long[] inFlightFences;
    private long[] frameSerials;
    private List<CommandPool.CommandBuffer> transferCbs;

    private Framebuffer boundFramebuffer;
    private RenderPass boundRenderPass;
    private final RenderPass.BeginInfo boundBeginInfo = new RenderPass.BeginInfo();

    private static int currentFrame = 0;
    private long nextSerial = 1;
    private long lastSubmittedSerial = 0;
    private long commandBufferGeneration = 0;

    private VkCommandBuffer currentCmdBuffer;
    private boolean recordingCmds = false;

    private final LongArrayList waitSemaphores = new LongArrayList();
    private final IntArrayList waitStages = new IntArrayList();
    private final LongArrayList signalSemaphores = new LongArrayList();

    private final int[] viewport = new int[4];
    private final int[] scissor = new int[4];

    private final List<Runnable> onResizeCallbacks = new ObjectArrayList<>();
    private final List<FrameListener> frameListeners = new ObjectArrayList<>();
    private final List<Runnable> preSubmitHooks = new ObjectArrayList<>();

    public Renderer() {
        device = Vulkan.getVkDevice();
        framesNum = Math.max(1, Initializer.CONFIG.frameQueueSize);
    }

    public static void setLineWidth(float width) {
        if (INSTANCE.boundFramebuffer == null) {
            return;
        }
        vkCmdSetLineWidth(INSTANCE.currentCmdBuffer, width);
    }

    private void init() {
        MemoryManager.createInstance(Renderer.getFramesNum());
        Vulkan.createStagingBuffers();

        drawer = new Drawer();
        drawer.createResources(framesNum);

        Uniforms.setupDefaultUniforms();
        PipelineManager.init();
        UploadManager.createInstance();

        allocateCommandBuffers();
        createSyncObjects();
    }

    private void allocateCommandBuffers() {
        if (mainCommandBuffers != null) {
            mainCommandBuffers.forEach(commandBuffer -> vkFreeCommandBuffers(device, Vulkan.getCommandPool(), commandBuffer));
        }

        mainCommandBuffers = new ArrayList<>(framesNum);

        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(getCommandPool());
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(framesNum);

            PointerBuffer pCommandBuffers = stack.mallocPointer(framesNum);

            int vkResult = vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers);
            if (vkResult != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate command buffers: %s".formatted(VkResult.decode(vkResult)));
            }

            for (int i = 0; i < framesNum; i++) {
                mainCommandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), device));
            }
        }

        if (transferCbs != null) {
            transferCbs.forEach(commandBuffer -> {
                vkResetCommandBuffer(commandBuffer.handle, 0);
                commandBuffer.reset();
            });
        }

        transferCbs = new ArrayList<>(framesNum);

        for (int i = 0; i < framesNum; i++) {
            transferCbs.add(DeviceManager.getTransferQueue().getCommandPool().getCommandBuffer());
        }
    }

    private void createSyncObjects() {
        inFlightFences = new long[framesNum];
        frameSerials = new long[framesNum];

        try (MemoryStack stack = stackPush()) {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pFence = stack.mallocLong(1);

            for (int i = 0; i < framesNum; i++) {
                if (vkCreateFence(device, fenceInfo, null, pFence) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create synchronization objects for the frame: " + i);
                }

                inFlightFences[i] = pFence.get(0);
                frameSerials[i] = 0;
            }
        }
    }

    private void destroySyncObjects() {
        for (int i = 0; i < framesNum; ++i) {
            vkDestroyFence(device, inFlightFences[i], null);
        }
    }

    /**
     * Registers work to record right before the frame's command buffer is submitted, while
     * recording is still possible (e.g. closing a pending transfer batch).
     */
    public void addPreSubmitHook(Runnable hook) {
        this.preSubmitHooks.add(hook);
    }

    public void removePreSubmitHook(Runnable hook) {
        this.preSubmitHooks.remove(hook);
    }

    public void addFrameListener(FrameListener listener) {
        this.frameListeners.add(listener);
    }

    public void removeFrameListener(FrameListener listener) {
        this.frameListeners.remove(listener);
    }

    /**
     * Starts recording the current frame if no command buffer is being recorded yet.
     * Every command recorded through the backend goes through here.
     */
    public void ensureRecording() {
        if (!this.recordingCmds) {
            this.beginFrame();
        }
    }

    public void beginFrame() {
        if (this.recordingCmds) {
            return;
        }

        Profiler p = Profiler.getMainProfiler();
        p.pop();
        p.round();
        p.push("Frame_fence");

        vkWaitForFences(device, inFlightFences[currentFrame], true, VUtil.UINT64_MAX);

        p.pop();
        p.push("Begin_rendering");

        // Submit transfers that were recorded between frames
        submitUploads();

        MemoryManager.getInstance().initFrame(currentFrame);
        drawer.setCurrentFrame(currentFrame);
        drawer.resetBuffers(currentFrame);
        Vulkan.getStagingBuffers().beginFrame(currentFrame);

        for (FrameListener listener : this.frameListeners) {
            listener.onBeginFrame(currentFrame);
        }

        WorldRenderer worldRenderer = WorldRenderer.getInstance();
        if (worldRenderer != null) {
            worldRenderer.uploadSections();
        }
        UploadManager.INSTANCE.submitUploads();

        resetDescriptors();

        currentCmdBuffer = mainCommandBuffers.get(currentFrame);
        vkResetCommandBuffer(currentCmdBuffer, 0);

        try (MemoryStack stack = stackPush()) {
            beginCommandBuffer(stack);
        }

        p.pop();
    }

    private void beginCommandBuffer(MemoryStack stack) {
        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
        beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
        beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

        int vkResult = vkBeginCommandBuffer(currentCmdBuffer, beginInfo);
        if (vkResult != VK_SUCCESS) {
            throw new RuntimeException("Failed to begin recording command buffer: %s".formatted(VkResult.decode(vkResult)));
        }

        recordingCmds = true;
        commandBufferGeneration++;
        boundPipelineHandle = 0;
        boundPipeline = null;

        resetDynamicState(currentCmdBuffer);
    }

    /**
     * Ends the current frame: submits the recorded commands and advances to the next frame slot.
     */
    public void submit() {
        if (!recordingCmds) {
            return;
        }

        Profiler p = Profiler.getMainProfiler();
        p.push("End_rendering");

        for (int i = 0; i < this.preSubmitHooks.size(); i++) {
            this.preSubmitHooks.get(i).run();
        }

        endRenderPass(currentCmdBuffer);

        int result = vkEndCommandBuffer(currentCmdBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer: %s".formatted(VkResult.decode(result)));
        }

        submitUploads();
        waitFences();

        try (MemoryStack stack = stackPush()) {
            submitCommandBuffer(stack, true);
        }

        Vulkan.getStagingBuffers().endFrame(currentFrame);

        recordingCmds = false;
        this.boundRenderPass = null;
        this.boundFramebuffer = null;
        this.boundPipelineHandle = 0;
        this.boundPipeline = null;

        currentFrame = (currentFrame + 1) % framesNum;

        p.pop();
        p.push("Post_rendering");
    }

    private void submitCommandBuffer(MemoryStack stack, boolean useSignalSemaphores) {
        VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
        submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);

        int syncWaitCount = Synchronization.INSTANCE.getWaitSemaphoreCount();
        int totalWaitSemaphores = syncWaitCount + this.waitSemaphores.size();

        if (totalWaitSemaphores > 0) {
            LongBuffer pWaitSemaphores = stack.mallocLong(totalWaitSemaphores);
            IntBuffer pWaitDstStageMask = stack.mallocInt(totalWaitSemaphores);

            Synchronization.INSTANCE.getWaitSemaphores(pWaitSemaphores);

            for (int i = 0; i < syncWaitCount; i++) {
                pWaitDstStageMask.put(i, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
            }

            for (int i = 0; i < this.waitSemaphores.size(); i++) {
                pWaitSemaphores.put(syncWaitCount + i, this.waitSemaphores.getLong(i));
                pWaitDstStageMask.put(syncWaitCount + i, this.waitStages.getInt(i));
            }

            pWaitSemaphores.position(0);
            pWaitSemaphores.limit(totalWaitSemaphores);

            submitInfo.pWaitSemaphores(pWaitSemaphores);
            submitInfo.waitSemaphoreCount(totalWaitSemaphores);
            submitInfo.pWaitDstStageMask(pWaitDstStageMask);
        }

        this.waitSemaphores.clear();
        this.waitStages.clear();

        submitInfo.pCommandBuffers(stack.pointers(currentCmdBuffer));

        if (useSignalSemaphores && !this.signalSemaphores.isEmpty()) {
            LongBuffer pSignalSemaphores = stack.mallocLong(this.signalSemaphores.size());
            for (int i = 0; i < this.signalSemaphores.size(); i++) {
                pSignalSemaphores.put(i, this.signalSemaphores.getLong(i));
            }
            submitInfo.pSignalSemaphores(pSignalSemaphores);
        }

        if (useSignalSemaphores) {
            this.signalSemaphores.clear();
        }

        long fence = inFlightFences[currentFrame];
        vkResetFences(device, fence);

        int vkResult = vkQueueSubmit(DeviceManager.getGraphicsQueue().vkQueue(), submitInfo, fence);
        if (vkResult != VK_SUCCESS) {
            vkResetFences(device, fence);
            throw new RuntimeException("Failed to submit draw command buffer: %s".formatted(VkResult.decode(vkResult)));
        }

        frameSerials[currentFrame] = nextSerial;
        lastSubmittedSerial = nextSerial;
        nextSerial++;

        // Semaphore waited command buffers will be reset right after waiting this command buffer's fence
        Synchronization.INSTANCE.scheduleCbReset();
    }

    /**
     * Submits the commands recorded so far, waits for them and resumes recording (restoring the
     * bound render pass). Used when results are needed before the end of the frame, e.g. when a
     * staging buffer is exhausted or a fence has to be waited on.
     */
    public void flushCmds() {
        if (!this.recordingCmds) {
            return;
        }

        RenderPass renderPass = this.boundRenderPass;
        Framebuffer framebuffer = this.boundFramebuffer;

        try (MemoryStack stack = stackPush()) {
            this.endRenderPass(currentCmdBuffer);

            int result = vkEndCommandBuffer(currentCmdBuffer);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to record command buffer: %s".formatted(VkResult.decode(result)));
            }

            submitUploads();
            waitFences();

            submitCommandBuffer(stack, false);

            vkWaitForFences(device, inFlightFences[currentFrame], true, VUtil.UINT64_MAX);

            vkResetCommandBuffer(currentCmdBuffer, 0);
            beginCommandBuffer(stack);

            if (renderPass != null) {
                this.boundBeginInfo.setResume(true);
                this.beginRenderPass(renderPass, framebuffer, this.boundBeginInfo);
                this.boundBeginInfo.setResume(false);

                setViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                setScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
            }
        }
    }

    /**
     * Submits any work recorded for the current frame without presenting. Used before
     * operations that need the device to be idle (e.g. swapchain recreation).
     */
    public void finishPendingWork() {
        if (this.recordingCmds) {
            this.submit();
        }
        else {
            submitUploads();
            waitFences();
        }
    }

    /**
     * Serial of the frame that is currently being recorded (or will be recorded next).
     */
    public long getCurrentSerial() {
        return this.nextSerial;
    }

    public long getCommandBufferGeneration() {
        return this.commandBufferGeneration;
    }

    /**
     * Waits until the work with the given serial has completed.
     *
     * @param timeoutNs timeout in nanoseconds, 0 to poll, negative to wait forever
     */
    public boolean awaitSerial(long serial, long timeoutNs) {
        if (serial > this.lastSubmittedSerial) {
            if (timeoutNs == 0L) {
                return false;
            }

            // Work has not been submitted yet, force a submit
            this.ensureRecording();
            this.flushCmds();
        }

        for (int i = 0; i < framesNum; i++) {
            if (frameSerials[i] == serial) {
                long fence = inFlightFences[i];

                if (timeoutNs == 0L) {
                    return vkGetFenceStatus(device, fence) == VK_SUCCESS;
                }

                long timeout = timeoutNs < 0L ? VUtil.UINT64_MAX : timeoutNs;
                return vkWaitForFences(device, fence, true, timeout) == VK_SUCCESS;
            }
        }

        // Frame slot has been reused, which only happens after its fence was waited on
        return true;
    }

    public void submitUploads() {
        var transferCb = transferCbs.get(currentFrame);

        if (transferCb.isRecording()) {
            final var transferQueue = DeviceManager.getTransferQueue();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                transferCb.submitCommands(stack, transferQueue.vkQueue(), true);
            }

            Synchronization.INSTANCE.addCommandBuffer(transferCb, true);

            transferCbs.set(currentFrame, transferQueue.getCommandPool().getCommandBuffer());
        }

        ImageUploadHelper.INSTANCE.submitCommands();
    }

    public void endRenderPass() {
        endRenderPass(currentCmdBuffer);
    }

    public void endRenderPass(VkCommandBuffer commandBuffer) {
        if (!recordingCmds || this.boundRenderPass == null)
            return;

        this.boundRenderPass.endRenderPass(commandBuffer);

        this.boundRenderPass = null;
        this.boundFramebuffer = null;
    }

    public void beginRenderPass(RenderPass renderPass, Framebuffer framebuffer, RenderPass.BeginInfo beginInfo) {
        this.ensureRecording();

        this.endRenderPass(currentCmdBuffer);

        try (MemoryStack stack = stackPush()) {
            framebuffer.beginRenderPass(currentCmdBuffer, renderPass, stack, beginInfo);
        }

        this.boundFramebuffer = framebuffer;
        this.boundRenderPass = renderPass;

        if (beginInfo != this.boundBeginInfo) {
            this.boundBeginInfo.setArea(beginInfo.x, beginInfo.y, beginInfo.width, beginInfo.height)
                               .setClearColor(beginInfo.clearR, beginInfo.clearG, beginInfo.clearB, beginInfo.clearA)
                               .setClearDepth(beginInfo.clearDepth)
                               .setResume(false);
        }

        // Dynamic state is reset by a new pipeline bind in the game's render pass abstraction
        setViewport(0, 0, framebuffer.getWidth(), framebuffer.getHeight());
        setScissor(beginInfo.x, beginInfo.y, beginInfo.width, beginInfo.height);
    }

    public void beginRenderPass(RenderPass renderPass, Framebuffer framebuffer) {
        RenderPass.BeginInfo beginInfo = new RenderPass.BeginInfo()
                .setArea(0, 0, framebuffer.getWidth(), framebuffer.getHeight())
                .setClearColor(VRenderSystem.clearColor.get(0), VRenderSystem.clearColor.get(1), VRenderSystem.clearColor.get(2), VRenderSystem.clearColor.get(3))
                .setClearDepth(VRenderSystem.clearDepthValue);

        beginRenderPass(renderPass, framebuffer, beginInfo);
    }

    public void addUsedPipeline(Pipeline pipeline) {
        usedPipelines.add(pipeline);
    }

    public void removeUsedPipeline(Pipeline pipeline) {
        usedPipelines.remove(pipeline);
    }

    private void waitFences() {
        // Make sure there are no uploads/transitions scheduled
        Synchronization.INSTANCE.waitFences();
    }

    private void resetDescriptors() {
        for (Pipeline pipeline : usedPipelines) {
            pipeline.resetDescriptorPool(currentFrame);
        }

        usedPipelines.clear();
        boundPipeline = null;
        boundPipelineHandle = 0;
    }

    /**
     * Called by the surface after the device went idle: applies a changed frame queue size.
     */
    public void onSurfaceReconfigured() {
        int newFramesNum = Math.max(1, Initializer.CONFIG.frameQueueSize);

        if (framesNum != newFramesNum) {
            UploadManager.INSTANCE.submitUploads();
            waitFences();
            Vulkan.waitIdle();

            destroySyncObjects();

            framesNum = newFramesNum;
            MemoryManager.getInstance().freeAllBuffers();
            MemoryManager.createInstance(newFramesNum);
            createStagingBuffers();
            allocateCommandBuffers();

            Pipeline.recreateDescriptorSets(framesNum);

            drawer.createResources(framesNum);

            createSyncObjects();

            for (FrameListener listener : this.frameListeners) {
                listener.onFramesNumChanged(framesNum);
            }

            currentFrame = 0;
        }

        this.onResizeCallbacks.forEach(Runnable::run);
    }

    public void cleanUpResources() {
        if (WorldRenderer.getInstance() != null) {
            WorldRenderer.getInstance().cleanUp();
        }
        destroySyncObjects();

        drawer.cleanUpResources();

        PipelineManager.destroyPipelines();
        VTextureSelector.getWhiteTexture().free();
    }

    public void addOnResizeCallback(Runnable runnable) {
        this.onResizeCallbacks.add(runnable);
    }

    public void bindGraphicsPipeline(GraphicsPipeline pipeline) {
        PipelineState currentState = PipelineState.getCurrentPipelineState(boundRenderPass);
        final long handle = pipeline.getHandle(currentState);

        if (boundPipelineHandle == handle) {
            return;
        }

        vkCmdBindPipeline(currentCmdBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
        boundPipelineHandle = handle;
        boundPipeline = pipeline;
        addUsedPipeline(pipeline);

        // VulkanMod's own pipelines bind their own descriptor sets, vertex buffers and push constants:
        // the backend render pass has to re-bind its state afterwards
        commandBufferGeneration++;
    }

    /**
     * Binds a raw pipeline handle (used by the backend pipelines).
     */
    public void bindPipelineHandle(long handle) {
        if (boundPipelineHandle == handle) {
            return;
        }

        vkCmdBindPipeline(currentCmdBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
        boundPipelineHandle = handle;
        boundPipeline = null;
    }

    public void uploadAndBindUBOs(Pipeline pipeline) {
        VkCommandBuffer commandBuffer = currentCmdBuffer;
        pipeline.bindDescriptorSets(commandBuffer, currentFrame);
    }

    public void pushConstants(Pipeline pipeline) {
        VkCommandBuffer commandBuffer = currentCmdBuffer;

        PushConstants pushConstants = pipeline.getPushConstants();

        try (MemoryStack stack = stackPush()) {
            ByteBuffer buffer = stack.malloc(pushConstants.getSize());
            long ptr = MemoryUtil.memAddress0(buffer);
            pushConstants.update(ptr);

            nvkCmdPushConstants(commandBuffer, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, pushConstants.getSize(), ptr);
        }

    }

    public Pipeline getBoundPipeline() {
        return boundPipeline;
    }

    public void setBoundFramebuffer(Framebuffer framebuffer) {
        this.boundFramebuffer = framebuffer;
    }

    public Framebuffer getBoundFramebuffer() {
        return boundFramebuffer;
    }

    public void setBoundRenderPass(RenderPass boundRenderPass) {
        this.boundRenderPass = boundRenderPass;
    }

    public RenderPass getBoundRenderPass() {
        return boundRenderPass;
    }

    public CommandPool.CommandBuffer getTransferCb() {
        return transferCbs.get(currentFrame);
    }

    /**
     * Registers a semaphore the next submit has to wait on.
     */
    public void addWaitSemaphore(long semaphore, int stageMask) {
        this.waitSemaphores.add(semaphore);
        this.waitStages.add(stageMask);
    }

    /**
     * Registers a semaphore the next frame submit signals.
     */
    public void addSignalSemaphore(long semaphore) {
        this.signalSemaphores.add(semaphore);
    }

    private static void resetDynamicState(VkCommandBuffer commandBuffer) {
        vkCmdSetDepthBias(commandBuffer, 0.0F, 0.0F, 0.0F);

        if (VRenderSystem.canSetLineWidth) {
            vkCmdSetLineWidth(commandBuffer, 1.0F);
        }
    }

    public static void setDepthBias(float constant, float slope) {
        if (!INSTANCE.recordingCmds)
            return;

        vkCmdSetDepthBias(INSTANCE.currentCmdBuffer, constant, 0.0f, slope);
    }

    public static void clearAttachments(int attachments) {
        clearAttachments(INSTANCE.currentCmdBuffer, attachments);
    }

    public static void clearAttachments(VkCommandBuffer commandBuffer, int attachments) {
        Framebuffer framebuffer = Renderer.getInstance().boundFramebuffer;
        if (framebuffer == null)
            return;

        clearAttachments(commandBuffer, attachments, 0, 0, framebuffer.getWidth(), framebuffer.getHeight(),
                         VRenderSystem.clearColor, VRenderSystem.clearDepthValue);
    }

    public static void clearAttachments(int attachments, int x, int y, int width, int height) {
        clearAttachments(INSTANCE.currentCmdBuffer, attachments, x, y, width, height,
                         VRenderSystem.clearColor, VRenderSystem.clearDepthValue);
    }

    public static void clearAttachments(VkCommandBuffer commandBuffer, int attachments, int x, int y, int width, int height,
                                        FloatBuffer clearColor, float clearDepth) {
        try (MemoryStack stack = stackPush()) {
            //ClearValues have to be different for each attachment to clear,
            //it seems it uses the same buffer: color and depth values override themselves
            VkClearValue colorValue = VkClearValue.calloc(stack);
            colorValue.color().float32(0, clearColor.get(0)).float32(1, clearColor.get(1)).float32(2, clearColor.get(2)).float32(3, clearColor.get(3));

            VkClearValue depthValue = VkClearValue.calloc(stack);
            depthValue.depthStencil().set(clearDepth, 0);

            int attachmentsCount = attachments == (GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT) ? 2 : 1;
            final VkClearAttachment.Buffer pAttachments = VkClearAttachment.malloc(attachmentsCount, stack);
            switch (attachments) {
                case GL_DEPTH_BUFFER_BIT -> {

                    VkClearAttachment clearDepthAttachment = pAttachments.get(0);
                    clearDepthAttachment.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                    clearDepthAttachment.colorAttachment(0);
                    clearDepthAttachment.clearValue(depthValue);
                }
                case GL_COLOR_BUFFER_BIT -> {

                    VkClearAttachment clearColorAttachment = pAttachments.get(0);
                    clearColorAttachment.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                    clearColorAttachment.colorAttachment(0);
                    clearColorAttachment.clearValue(colorValue);
                }
                case GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT -> {

                    VkClearAttachment clearColorAttachment = pAttachments.get(0);
                    clearColorAttachment.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                    clearColorAttachment.colorAttachment(0);
                    clearColorAttachment.clearValue(colorValue);

                    VkClearAttachment clearDepthAttachment = pAttachments.get(1);
                    clearDepthAttachment.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                    clearDepthAttachment.colorAttachment(0);
                    clearDepthAttachment.clearValue(depthValue);
                }
                default -> throw new RuntimeException("unexpected value");
            }

            //Rect to clear
            VkRect2D renderArea = VkRect2D.malloc(stack);
            renderArea.offset().set(x, y);
            renderArea.extent().set(width, height);

            VkClearRect.Buffer pRect = VkClearRect.malloc(1, stack);
            pRect.rect(renderArea);
            pRect.baseArrayLayer(0);
            pRect.layerCount(1);

            vkCmdClearAttachments(commandBuffer, pAttachments, pRect);
        }
    }

    public static void resetViewport() {
        Framebuffer framebuffer = INSTANCE.boundFramebuffer;

        if (framebuffer == null) {
            return;
        }

        int width = framebuffer.getWidth();
        int height = framebuffer.getHeight();

        if (width > 0 && height > 0) {
            setViewport(0, 0, width, height);
        }
    }

    public static void setViewport(int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            setViewport(x, y, width, height, stack);
        }
    }

    /**
     * Sets the viewport. Like the game's own Vulkan backend, the viewport is not flipped:
     * framebuffers are stored with the same (bottom-up) memory layout OpenGL uses and the
     * image is flipped when it is blitted to the swapchain.
     */
    public static void setViewport(int x, int y, int width, int height, MemoryStack stack) {
        if (!INSTANCE.recordingCmds)
            return;

        INSTANCE.viewport[0] = x;
        INSTANCE.viewport[1] = y;
        INSTANCE.viewport[2] = width;
        INSTANCE.viewport[3] = height;

        VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
        viewport.x(x);
        viewport.y(y);
        viewport.width(width);
        viewport.height(height);
        viewport.minDepth(0.0f);
        viewport.maxDepth(1.0f);

        vkCmdSetViewport(INSTANCE.currentCmdBuffer, 0, viewport);
    }

    public static void setScissor(int x, int y, int width, int height) {
        if (!INSTANCE.recordingCmds)
            return;

        INSTANCE.scissor[0] = x;
        INSTANCE.scissor[1] = y;
        INSTANCE.scissor[2] = width;
        INSTANCE.scissor[3] = height;

        try (MemoryStack stack = stackPush()) {
            VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
            scissor.offset().set(Math.max(0, x), Math.max(0, y));
            scissor.extent().set(Math.max(0, width), Math.max(0, height));

            vkCmdSetScissor(INSTANCE.currentCmdBuffer, 0, scissor);
        }
    }

    public static void resetScissor() {
        if (INSTANCE.boundFramebuffer == null)
            return;

        setScissor(0, 0, INSTANCE.boundFramebuffer.getWidth(), INSTANCE.boundFramebuffer.getHeight());
    }

    public static void pushDebugSection(String s) {
        if (!INSTANCE.recordingCmds)
            return;

        Vulkan.Debug.pushDebugSection(INSTANCE.currentCmdBuffer, s);
    }

    public static void popDebugSection() {
        if (!INSTANCE.recordingCmds)
            return;

        Vulkan.Debug.popDebugSection(INSTANCE.currentCmdBuffer);
    }

    public static int getFramesNum() {
        return INSTANCE.framesNum;
    }

    public static VkCommandBuffer getCommandBuffer() {
        return INSTANCE.currentCmdBuffer;
    }

    public static boolean isRecording() {
        return INSTANCE != null && INSTANCE.recordingCmds;
    }
}

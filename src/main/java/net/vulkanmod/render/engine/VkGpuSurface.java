package net.vulkanmod.render.engine;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.VkResult;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Collection;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Backend window surface: owns the swapchain and the semaphores used to synchronize image
 * acquisition and presentation with the frame submit.
 */
public class VkGpuSurface implements GpuSurfaceBackend, Renderer.FrameListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long ACQUIRE_TIMEOUT_NS = 5_000_000_000L;

    private final Renderer renderer;
    private final SwapChain swapChain = new SwapChain();

    private long[] imageAvailableSemaphores = new long[0];
    private long[] renderFinishedSemaphores = new long[0];

    private int currentImageIndex = -1;
    private int acquireSemaphoreIndex = 0;
    private boolean acquired;
    private boolean suboptimal;
    private boolean closed;

    public VkGpuSurface(long windowHandle) {
        this.renderer = Renderer.getInstance();

        Vulkan.createSurface(windowHandle);

        this.renderer.addFrameListener(this);
    }

    @Override
    public void onBeginFrame(int frame) {
    }

    @Override
    public void onFramesNumChanged(int framesNum) {
        this.acquireSemaphoreIndex = 0;
    }

    private void createSemaphores(int frames, int images) {
        destroySemaphores();

        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack);
            semaphoreInfo.sType$Default();

            LongBuffer pSemaphore = stack.mallocLong(1);

            this.imageAvailableSemaphores = new long[frames];
            for (int i = 0; i < frames; i++) {
                Vulkan.checkResult(vkCreateSemaphore(Vulkan.getVkDevice(), semaphoreInfo, null, pSemaphore), "Failed to create semaphore");
                this.imageAvailableSemaphores[i] = pSemaphore.get(0);
            }

            this.renderFinishedSemaphores = new long[images];
            for (int i = 0; i < images; i++) {
                Vulkan.checkResult(vkCreateSemaphore(Vulkan.getVkDevice(), semaphoreInfo, null, pSemaphore), "Failed to create semaphore");
                this.renderFinishedSemaphores[i] = pSemaphore.get(0);
            }
        }

        this.acquireSemaphoreIndex = 0;
    }

    private void destroySemaphores() {
        for (long semaphore : this.imageAvailableSemaphores) {
            vkDestroySemaphore(Vulkan.getVkDevice(), semaphore, null);
        }
        for (long semaphore : this.renderFinishedSemaphores) {
            vkDestroySemaphore(Vulkan.getVkDevice(), semaphore, null);
        }

        this.imageAvailableSemaphores = new long[0];
        this.renderFinishedSemaphores = new long[0];
    }

    @Override
    public void configure(GpuSurface.Configuration config) throws SurfaceException {
        // Finish any pending work before touching the swapchain
        this.renderer.finishPendingWork();
        Vulkan.waitIdle();

        this.acquired = false;
        this.suboptimal = false;
        this.currentImageIndex = -1;

        try {
            boolean hasImages = this.swapChain.create(config.width(), config.height(), SwapChain.toVkPresentMode(config.presentMode()));

            if (!hasImages) {
                destroySemaphores();
                throw new SurfaceException("Surface has no size");
            }

            // Semaphores are recreated so none of them is left in a signaled state
            createSemaphores(Math.max(Renderer.getFramesNum(), 2), this.swapChain.getImagesNum());
        } catch (RuntimeException e) {
            throw new SurfaceException(e);
        }

        this.renderer.onSurfaceReconfigured();
    }

    @Override
    public boolean isSuboptimal() {
        return this.suboptimal;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        if (this.acquired) {
            return;
        }

        if (!this.swapChain.hasImages() || this.imageAvailableSemaphores.length == 0) {
            throw new SurfaceException("Swapchain is not configured");
        }

        // Starts the frame (waits for the frame slot to be free) before using its semaphore
        this.renderer.ensureRecording();

        this.acquireSemaphoreIndex = (this.acquireSemaphoreIndex + 1) % this.imageAvailableSemaphores.length;
        long semaphore = this.imageAvailableSemaphores[this.acquireSemaphoreIndex];

        try (MemoryStack stack = stackPush()) {
            IntBuffer pImageIndex = stack.mallocInt(1);

            int result = vkAcquireNextImageKHR(Vulkan.getVkDevice(), this.swapChain.getId(), ACQUIRE_TIMEOUT_NS,
                                               semaphore, VK_NULL_HANDLE, pImageIndex);

            if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                this.suboptimal = true;
                throw new SurfaceException("Swapchain is out of date");
            }
            else if (result == VK_SUBOPTIMAL_KHR) {
                this.suboptimal = true;
            }
            else if (result != VK_SUCCESS) {
                throw new SurfaceException("Cannot acquire next swap chain image: %s".formatted(VkResult.decode(result)));
            }

            this.currentImageIndex = pImageIndex.get(0);
        }

        this.acquired = true;
        this.renderer.addWaitSemaphore(semaphore, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
        if (!this.acquired) {
            throw new IllegalStateException("No swapchain image acquired");
        }

        this.renderer.ensureRecording();
        this.renderer.endRenderPass();

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        VulkanImage srcImage = ((VkTextureView) textureView).getVulkanImage();
        VulkanImage dstImage = this.swapChain.getImage(this.currentImageIndex);

        int copyWidth = Math.min(this.swapChain.getWidth(), textureView.getWidth(0));
        int copyHeight = Math.min(this.swapChain.getHeight(), textureView.getHeight(0));

        try (MemoryStack stack = stackPush()) {
            int prevLayout = srcImage.getCurrentLayout();
            srcImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            dstImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            // Render targets use the OpenGL (bottom-up) memory layout, so the blit flips vertically
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
            blit.srcOffsets(1, VkOffset3D.calloc(stack).set(copyWidth, copyHeight, 1));
            blit.srcSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(textureView.baseMipLevel())
                .baseArrayLayer(0)
                .layerCount(1);

            blit.dstOffsets(0, VkOffset3D.calloc(stack).set(0, copyHeight, 0));
            blit.dstOffsets(1, VkOffset3D.calloc(stack).set(copyWidth, 0, 1));
            blit.dstSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

            vkCmdBlitImage(commandBuffer, srcImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           dstImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_NEAREST);

            dstImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            srcImage.transitionImageLayout(stack, commandBuffer,
                                           prevLayout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : prevLayout);
        }

        this.renderer.addSignalSemaphore(this.renderFinishedSemaphores[this.currentImageIndex]);
    }

    @Override
    public void present() {
        if (!this.acquired) {
            return;
        }

        // The game submits before presenting; make sure the frame is really submitted
        this.renderer.finishPendingWork();

        try (MemoryStack stack = stackPush()) {
            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType$Default();
            presentInfo.pWaitSemaphores(stack.longs(this.renderFinishedSemaphores[this.currentImageIndex]));
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(this.swapChain.getId()));
            presentInfo.pImageIndices(stack.ints(this.currentImageIndex));

            int result = vkQueuePresentKHR(DeviceManager.getPresentQueue().vkQueue(), presentInfo);

            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
                this.suboptimal = true;
            }
            else if (result != VK_SUCCESS) {
                LOGGER.error("Failed to present rendered frame: {}", VkResult.decode(result));
                this.suboptimal = true;
            }
        }

        this.acquired = false;
        this.currentImageIndex = -1;
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return SwapChain.querySupportedPresentModes();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;

        this.renderer.finishPendingWork();
        Vulkan.waitIdle();

        this.renderer.removeFrameListener(this);
        destroySemaphores();
        this.swapChain.cleanUp();
        Vulkan.destroySurface();
    }
}

package net.vulkanmod.render.engine;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.function.Supplier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Backend render pass: records draws into the current frame command buffer between
 * {@link VkCommandEncoder#createRenderPass} and {@link VkCommandEncoder#submitRenderPass}.
 */
public class VkRenderPass implements RenderPassBackend {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_VERTEX_BUFFERS = 16;
    static final int MAX_PUSH_CONSTANTS = 256;
    static final int MAX_DYNAMIC_OFFSETS = 32;

    private final Renderer renderer;
    private final Framebuffer framebuffer;
    private final net.vulkanmod.vulkan.framebuffer.RenderPass vkRenderPass;
    private final RenderPass.RenderArea renderArea;
    private final int outputWidth;
    private final int outputHeight;
    private final boolean hasDepth;
    private final Supplier<String> label;

    private long commandBufferGeneration;

    @Nullable private VkRenderPipeline pipeline;
    private Object[] uniformValues = new Object[0];
    private long[] boundKeys = new long[0];
    private boolean descriptorsDirty;
    private boolean uniformsDirty;
    private long currentSet;
    private long pipelineHandle;
    private final IntBuffer dynamicOffsets;

    // State known to be bound in the command buffer, to skip redundant binds
    private long boundSet;
    private final int[] boundDynamicOffsets = new int[MAX_DYNAMIC_OFFSETS];
    private final long[] boundVertexBufferIds = new long[MAX_VERTEX_BUFFERS];
    private final long[] boundVertexBufferOffsets = new long[MAX_VERTEX_BUFFERS];
    private long boundIndexBufferId;
    private int boundIndexType;

    private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    @Nullable private GpuBuffer indexBuffer;
    private IndexType indexType = IndexType.INT;

    private final ByteBuffer pushConstants;
    private int pushConstantsSize;

    private int pushedDebugGroups;

    /**
     * @param pushConstants  scratch buffer of {@link #MAX_PUSH_CONSTANTS} bytes owned by the encoder
     * @param dynamicOffsets scratch buffer of {@link #MAX_DYNAMIC_OFFSETS} ints owned by the encoder
     */
    public VkRenderPass(Renderer renderer, Framebuffer framebuffer, net.vulkanmod.vulkan.framebuffer.RenderPass vkRenderPass,
                        RenderPass.RenderArea renderArea, int outputWidth, int outputHeight, boolean hasDepth, Supplier<String> label,
                        ByteBuffer pushConstants, IntBuffer dynamicOffsets) {
        this.renderer = renderer;
        this.pushConstants = pushConstants;
        this.dynamicOffsets = dynamicOffsets;
        this.framebuffer = framebuffer;
        this.vkRenderPass = vkRenderPass;
        this.renderArea = renderArea;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.hasDepth = hasDepth;
        this.label = label;
        this.commandBufferGeneration = renderer.getCommandBufferGeneration();
    }

    private void resetBoundState() {
        this.boundSet = 0L;
        Arrays.fill(this.boundVertexBufferIds, 0L);
        this.boundIndexBufferId = 0L;
    }

    private VkCommandBuffer commandBuffer() {
        return Renderer.getCommandBuffer();
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
        this.pushedDebugGroups++;
        Renderer.pushDebugSection(label.get());
    }

    @Override
    public void popDebugGroup() {
        if (this.pushedDebugGroups > 0) {
            this.pushedDebugGroups--;
            Renderer.popDebugSection();
        }
    }

    @Override
    public void setPipeline(BackendRenderPipeline pipeline) {
        if (!(pipeline instanceof VkRenderPipeline vkPipeline)) {
            throw new IllegalArgumentException("Pipeline must be a VkRenderPipeline");
        }

        if (this.pipeline == vkPipeline) {
            return;
        }

        this.pipeline = vkPipeline;

        int uniformCount = vkPipeline.getUniformCount();
        this.uniformValues = new Object[uniformCount];
        this.boundKeys = new long[uniformCount * 2];
        Arrays.fill(this.boundKeys, Long.MIN_VALUE);

        // The attachment formats don't change within a pass, so the pipeline handle is resolved once
        this.pipelineHandle = vkPipeline.getHandle(this.vkRenderPass.getColorFormat(), this.vkRenderPass.getDepthFormat());

        if (vkPipeline.getDynamicOffsetCount() > MAX_DYNAMIC_OFFSETS) {
            throw new IllegalStateException("Pipeline uses more than " + MAX_DYNAMIC_OFFSETS + " dynamic uniform buffers");
        }

        this.descriptorsDirty = true;
        this.uniformsDirty = true;
        this.pushConstantsSize = 0;

        // A pipeline with a different layout may disturb the bound set, so it is always rebound
        this.boundSet = 0L;

        bindPipeline();
    }

    private void bindPipeline() {
        if (this.pipeline == null) {
            return;
        }

        this.renderer.bindPipelineHandle(this.pipelineHandle);
    }

    @Override
    public void setUniform(int index, @Nullable Object value) {
        if (index < 0 || index >= this.uniformValues.length) {
            return;
        }

        this.uniformValues[index] = value;
        this.uniformsDirty = true;
    }

    @Override
    public void pushConstants(ByteBuffer value) {
        if (this.pipeline == null) {
            throw new IllegalStateException("Must bind pipeline before pushing constants");
        }

        int size = Math.min(value.remaining(), this.pipeline.getCreateInfo().pushConstantsSize());
        size = Math.min(size, MAX_PUSH_CONSTANTS);

        MemoryUtil.memCopy(MemoryUtil.memAddress(value), MemoryUtil.memAddress(this.pushConstants), size);
        this.pushConstantsSize = size;

        recordPushConstants();
    }

    private void recordPushConstants() {
        if (this.pipeline == null || this.pushConstantsSize == 0) {
            return;
        }

        nvkCmdPushConstants(commandBuffer(), this.pipeline.getLayout(), this.pipeline.getPushConstantStages(), 0,
                            this.pushConstantsSize, MemoryUtil.memAddress(this.pushConstants));
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        Renderer.setScissor(x, y, width, height);
    }

    @Override
    public void disableScissor() {
        if (this.renderArea != null) {
            Renderer.setScissor(this.renderArea.x(), this.renderArea.y(), this.renderArea.width(), this.renderArea.height());
        }
        else {
            Renderer.setScissor(0, 0, this.outputWidth, this.outputHeight);
        }
    }

    @Override
    public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
        if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
            throw new IllegalArgumentException("Vertex buffer slot is out of range: " + slot);
        }

        this.vertexBuffers[slot] = vertexBuffer;

        if (vertexBuffer != null) {
            bindVertexBuffer(slot, vertexBuffer);
        }
    }

    private void bindVertexBuffer(int slot, GpuBufferSlice vertexBuffer) {
        long id = ((VkGpuBuffer) vertexBuffer.buffer()).getId();
        long offset = vertexBuffer.offset();

        if (this.boundVertexBufferIds[slot] == id && this.boundVertexBufferOffsets[slot] == offset) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            vkCmdBindVertexBuffers(commandBuffer(), slot, stack.longs(id), stack.longs(offset));
        }

        this.boundVertexBufferIds[slot] = id;
        this.boundVertexBufferOffsets[slot] = offset;
    }

    @Override
    public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
        this.indexBuffer = indexBuffer;
        this.indexType = indexType;

        bindIndexBuffer();
    }

    private void bindIndexBuffer() {
        if (this.indexBuffer == null) {
            return;
        }

        long id = ((VkGpuBuffer) this.indexBuffer).getId();
        int type = VkConst.of(this.indexType);

        if (this.boundIndexBufferId == id && this.boundIndexType == type) {
            return;
        }

        vkCmdBindIndexBuffer(commandBuffer(), id, 0L, type);

        this.boundIndexBufferId = id;
        this.boundIndexType = type;
    }

    /**
     * Makes sure pipeline, descriptors and buffers are bound before a draw. The command buffer
     * may have been flushed and restarted since the last draw, in which case all state is re-bound.
     */
    private void prepareDraw() {
        if (this.pipeline == null) {
            throw new IllegalStateException("Can't draw without a render pipeline");
        }

        long generation = this.renderer.getCommandBufferGeneration();
        if (generation != this.commandBufferGeneration) {
            this.commandBufferGeneration = generation;
            resetBoundState();

            for (int i = 0; i < MAX_VERTEX_BUFFERS; i++) {
                if (this.vertexBuffers[i] != null) {
                    bindVertexBuffer(i, this.vertexBuffers[i]);
                }
            }
            bindIndexBuffer();
            recordPushConstants();
            this.descriptorsDirty = true;
        }

        bindPipeline();

        if (this.uniformsDirty || this.descriptorsDirty) {
            updateDescriptors();
        }
    }

    private void updateDescriptors() {
        int uniformCount = this.pipeline.getUniformCount();
        this.uniformsDirty = false;

        if (uniformCount == 0) {
            this.descriptorsDirty = false;
            return;
        }

        boolean needsNewSet = this.descriptorsDirty || this.currentSet == 0L;

        // Check whether the bound resources changed (dynamic offsets alone don't need a new set)
        for (int i = 0; i < uniformCount && !needsNewSet; i++) {
            if (bindingKey(i, false) != this.boundKeys[i * 2] || bindingKey(i, true) != this.boundKeys[i * 2 + 1]) {
                needsNewSet = true;
            }
        }

        try (MemoryStack stack = stackPush()) {
            if (needsNewSet) {
                for (int i = 0; i < uniformCount; i++) {
                    this.boundKeys[i * 2] = bindingKey(i, false);
                    this.boundKeys[i * 2 + 1] = bindingKey(i, true);
                }

                int frame = Renderer.getCurrentFrame();

                // Sets written earlier this frame for the same resources are reused
                long cachedSet = this.pipeline.findDescriptorSet(frame, this.boundKeys);
                if (cachedSet != 0L) {
                    this.currentSet = cachedSet;
                }
                else {
                    this.currentSet = this.pipeline.allocateDescriptorSet(frame);
                    writeDescriptorSet(stack, uniformCount);
                    this.pipeline.cacheDescriptorSet(frame, this.boundKeys, this.currentSet);
                }
            }

            int dynamicCount = this.pipeline.getDynamicOffsetCount();
            boolean changed = this.currentSet != this.boundSet;

            for (int i = 0; i < uniformCount; i++) {
                int dynamicIndex = this.pipeline.getDynamicOffsetIndex(i);
                if (dynamicIndex >= 0) {
                    int offset = this.uniformValues[i] instanceof GpuBufferSlice slice ? (int) slice.offset() : 0;
                    if (this.boundDynamicOffsets[dynamicIndex] != offset) {
                        this.boundDynamicOffsets[dynamicIndex] = offset;
                        changed = true;
                    }
                    this.dynamicOffsets.put(dynamicIndex, offset);
                }
            }

            // Same set with the same offsets is already bound
            if (changed) {
                this.dynamicOffsets.position(0);
                this.dynamicOffsets.limit(dynamicCount);

                vkCmdBindDescriptorSets(commandBuffer(), VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeline.getLayout(),
                                        0, stack.longs(this.currentSet), dynamicCount > 0 ? this.dynamicOffsets : null);

                this.dynamicOffsets.limit(this.dynamicOffsets.capacity());
                this.boundSet = this.currentSet;
            }
        }

        this.descriptorsDirty = false;
    }

    /**
     * Identity of the resource bound at a binding, as two longs (handle, then size or sampler)
     * so that distinct resources never share a key. Dynamic buffer offsets are not part of it.
     */
    private long bindingKey(int binding, boolean second) {
        Object value = this.uniformValues[binding];

        if (value instanceof GpuBufferSlice slice) {
            return second ? slice.length() : ((VkGpuBuffer) slice.buffer()).getId();
        }
        else if (value instanceof TextureViewAndSampler pair) {
            return second ? ((VkSampler) pair.sampler()).getId() : ((VkTextureView) pair.view()).getImageView();
        }

        return 0L;
    }

    private void writeDescriptorSet(MemoryStack stack, int uniformCount) {
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(uniformCount, stack);
        int writeCount = 0;

        for (int i = 0; i < uniformCount; i++) {
            Object value = this.uniformValues[i];

            if (value == null) {
                continue;
            }

            int descriptorType = this.pipeline.getDescriptorType(i);
            VkWriteDescriptorSet write = writes.get(writeCount);
            write.sType$Default();
            write.dstSet(this.currentSet);
            write.dstBinding(i);
            write.dstArrayElement(0);
            write.descriptorType(descriptorType);
            write.descriptorCount(1);

            switch (descriptorType) {
                case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC -> {
                    if (!(value instanceof GpuBufferSlice slice)) {
                        continue;
                    }

                    VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                    bufferInfo.buffer(((VkGpuBuffer) slice.buffer()).getId());
                    bufferInfo.offset(0L);
                    bufferInfo.range(slice.length());
                    write.pBufferInfo(bufferInfo);
                }
                case VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER -> {
                    if (!(value instanceof TextureViewAndSampler pair)) {
                        continue;
                    }

                    VkTextureView view = (VkTextureView) pair.view();
                    VkSampler sampler = (VkSampler) pair.sampler();

                    VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
                    imageInfo.imageView(view.getImageView());
                    imageInfo.sampler(sampler.getId());
                    imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    write.pImageInfo(imageInfo);
                }
                case VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER -> {
                    if (!(value instanceof GpuBufferSlice slice)) {
                        continue;
                    }

                    long bufferView = ((VkGpuBuffer) slice.buffer()).getBufferView(this.pipeline.getTexelFormat(i));
                    write.pTexelBufferView(stack.longs(bufferView));
                }
                default -> {
                    continue;
                }
            }

            writeCount++;
        }

        if (writeCount > 0) {
            writes.limit(writeCount);
            vkUpdateDescriptorSets(Vulkan.getVkDevice(), writes, null);
        }
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        prepareDraw();
        vkCmdDrawIndexed(commandBuffer(), indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        prepareDraw();
        VkCommandBuffer commandBuffer = commandBuffer();

        // Interleaved (indexCount, firstIndex, vertexOffset) triples
        for (int i = 0; i < drawCount; i++) {
            int base = drawParameters.position() + i * 3;
            vkCmdDrawIndexed(commandBuffer, drawParameters.get(base), instanceCount, drawParameters.get(base + 1), drawParameters.get(base + 2), firstInstance);
        }
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
        prepareDraw();
        VkCommandBuffer commandBuffer = commandBuffer();

        int indexSize = this.indexType.bytes;
        for (int i = 0; i < drawCount; i++) {
            int firstIndex = (int) (firstIndexOffsets.get(firstIndexOffsets.position() + i) / indexSize);
            vkCmdDrawIndexed(commandBuffer, indexCounts.get(indexCounts.position() + i), 1, firstIndex, vertexOffsets.get(vertexOffsets.position() + i), 0);
        }
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        prepareDraw();
        VkGpuBuffer buffer = (VkGpuBuffer) commands.buffer();
        vkCmdDrawIndexedIndirect(commandBuffer(), buffer.getId(), commands.offset(), drawCount, VkDrawIndexedIndirectCommand.SIZEOF);
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        prepareDraw();
        vkCmdDraw(commandBuffer(), vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        prepareDraw();
        VkCommandBuffer commandBuffer = commandBuffer();

        // Interleaved (vertexCount, firstVertex) pairs
        for (int i = 0; i < drawCount; i++) {
            int base = drawParameters.position() + i * 2;
            vkCmdDraw(commandBuffer, drawParameters.get(base), instanceCount, drawParameters.get(base + 1), firstInstance);
        }
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        prepareDraw();
        VkCommandBuffer commandBuffer = commandBuffer();

        for (int i = 0; i < drawCount; i++) {
            vkCmdDraw(commandBuffer, vertexCounts.get(vertexCounts.position() + i), 1, firstVertices.get(firstVertices.position() + i), 0);
        }
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        prepareDraw();
        VkGpuBuffer buffer = (VkGpuBuffer) commands.buffer();
        vkCmdDrawIndirect(commandBuffer(), buffer.getId(), commands.offset(), drawCount, VkDrawIndirectCommand.SIZEOF);
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        ((VkQueryPool) pool).writeTimestamp(commandBuffer(), index);
    }

    public Framebuffer getFramebuffer() {
        return this.framebuffer;
    }

    public boolean hasDepth() {
        return this.hasDepth;
    }

    /**
     * Called by the encoder when the pass is submitted.
     */
    void finish() {
        while (this.pushedDebugGroups > 0) {
            popDebugGroup();
        }
    }
}

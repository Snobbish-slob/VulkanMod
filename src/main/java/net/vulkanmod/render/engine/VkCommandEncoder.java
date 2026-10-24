package net.vulkanmod.render.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Backend command encoder. All commands are recorded in order into the current frame's command
 * buffer, which is started lazily and submitted when the game calls {@link #submit()}.
 */
public class VkCommandEncoder implements CommandEncoderBackend {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final VkGpuDevice device;
    private final Renderer renderer;
    private final VkTransientMemory transientMemory;

    private final Long2ObjectOpenHashMap<net.vulkanmod.vulkan.framebuffer.RenderPass> renderPassCache = new Long2ObjectOpenHashMap<>();

    @Nullable private VkRenderPass currentRenderPass;

    public VkCommandEncoder(VkGpuDevice device) {
        this.device = device;
        this.renderer = Renderer.getInstance();
        this.transientMemory = new VkTransientMemory(this.renderer);
    }

    private VkCommandBuffer commandBuffer() {
        this.renderer.ensureRecording();
        return Renderer.getCommandBuffer();
    }

    private void checkNotInRenderPass() {
        if (this.currentRenderPass != null) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        }
    }

    @Override
    public void submit() {
        this.renderer.submit();
    }

    @Override
    public TransientMemory transientMemory() {
        return this.transientMemory;
    }

    // Render passes

    private net.vulkanmod.vulkan.framebuffer.RenderPass getRenderPass(int colorFormat, int depthFormat, boolean clearColor, boolean clearDepth) {
        long key = ((long) colorFormat << 34) | ((long) depthFormat << 2) | (clearColor ? 2 : 0) | (clearDepth ? 1 : 0);

        net.vulkanmod.vulkan.framebuffer.RenderPass renderPass = this.renderPassCache.get(key);
        if (renderPass == null) {
            net.vulkanmod.vulkan.framebuffer.RenderPass.AttachmentInfo colorInfo = null;
            net.vulkanmod.vulkan.framebuffer.RenderPass.AttachmentInfo depthInfo = null;

            if (colorFormat != VK_FORMAT_UNDEFINED) {
                colorInfo = new net.vulkanmod.vulkan.framebuffer.RenderPass.AttachmentInfo(net.vulkanmod.vulkan.framebuffer.RenderPass.AttachmentInfo.Type.COLOR, colorFormat)
                        .setOps(clearColor ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE)
                        .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }

            if (depthFormat != VK_FORMAT_UNDEFINED) {
                depthInfo = new net.vulkanmod.vulkan.framebuffer.RenderPass.AttachmentInfo(net.vulkanmod.vulkan.framebuffer.RenderPass.AttachmentInfo.Type.DEPTH, depthFormat)
                        .setOps(clearDepth ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE)
                        .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }

            renderPass = new net.vulkanmod.vulkan.framebuffer.RenderPass(colorInfo, depthInfo);
            this.renderPassCache.put(key, renderPass);
        }

        return renderPass;
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        if (this.currentRenderPass != null) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }

        this.renderer.ensureRecording();

        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments = descriptor.colorAttachments();
        if (colorAttachments.size() > 1) {
            throw new UnsupportedOperationException("VulkanMod supports a single color attachment, got " + colorAttachments.size());
        }

        RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment = colorAttachments.isEmpty() ? null : colorAttachments.get(0);
        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();

        VkTextureView colorView = colorAttachment != null ? (VkTextureView) colorAttachment.textureView() : null;
        VkTextureView depthView = depthAttachment != null ? (VkTextureView) depthAttachment.textureView() : null;

        VulkanImage colorImage = colorView != null ? colorView.getVulkanImage() : null;
        VulkanImage depthImage = depthView != null ? depthView.getVulkanImage() : null;

        VkTextureView sizeView = colorView != null ? colorView : depthView;
        int width = sizeView.getWidth(0);
        int height = sizeView.getHeight(0);

        Framebuffer framebuffer = Framebuffer.builder(colorImage, depthImage)
                                             .setAttachmentViews(colorView != null ? colorView.getImageView() : 0L,
                                                                 depthView != null ? depthView.getImageView() : 0L)
                                             .setSize(width, height)
                                             .build();

        boolean clearColor = colorAttachment != null && colorAttachment.clearValue().isPresent();
        boolean clearDepth = depthAttachment != null && depthAttachment.clearValue().isPresent();

        net.vulkanmod.vulkan.framebuffer.RenderPass renderPass = getRenderPass(
                colorImage != null ? colorImage.format : VK_FORMAT_UNDEFINED,
                depthImage != null ? depthImage.format : VK_FORMAT_UNDEFINED,
                clearColor, clearDepth);

        RenderPass.RenderArea renderArea = descriptor.renderArea();

        net.vulkanmod.vulkan.framebuffer.RenderPass.BeginInfo beginInfo = new net.vulkanmod.vulkan.framebuffer.RenderPass.BeginInfo()
                .setArea(renderArea.x(), renderArea.y(), renderArea.width(), renderArea.height());

        if (clearColor) {
            Vector4fc color = colorAttachment.clearValue().get();
            beginInfo.setClearColor(color.x(), color.y(), color.z(), color.w());
        }
        if (clearDepth) {
            beginInfo.setClearDepth((float) depthAttachment.clearValue().getAsDouble());
        }

        Renderer.pushDebugSection(descriptor.label().get());
        this.renderer.beginRenderPass(renderPass, framebuffer, beginInfo);

        this.currentRenderPass = new VkRenderPass(this.renderer, framebuffer, renderPass, renderArea, width, height,
                                                  depthView != null, descriptor.label());
        return this.currentRenderPass;
    }

    @Override
    public void submitRenderPass() {
        if (this.currentRenderPass == null) {
            throw new IllegalStateException("Can't submit a render pass if one isn't open");
        }

        this.currentRenderPass.finish();
        this.currentRenderPass = null;

        this.renderer.endRenderPass();
        Renderer.popDebugSection();
    }

    // Clears

    private static void setClearColor(VkClearColorValue clearValue, Vector4fc color) {
        clearValue.float32(0, color.x()).float32(1, color.y()).float32(2, color.z()).float32(3, color.w());
    }

    private void clearColorImage(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image, Vector4fc clearColor) {
        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        VkClearColorValue clearValue = VkClearColorValue.calloc(stack);
        setClearColor(clearValue, clearColor);

        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        range.baseMipLevel(0);
        range.levelCount(VK_REMAINING_MIP_LEVELS);
        range.baseArrayLayer(0);
        range.layerCount(VK_REMAINING_ARRAY_LAYERS);

        vkCmdClearColorImage(commandBuffer, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearValue, range);

        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    private void clearDepthImage(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image, double clearDepth) {
        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        VkClearDepthStencilValue clearValue = VkClearDepthStencilValue.calloc(stack);
        clearValue.set((float) clearDepth, 0);

        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.aspectMask(image.aspect);
        range.baseMipLevel(0);
        range.levelCount(VK_REMAINING_MIP_LEVELS);
        range.baseArrayLayer(0);
        range.layerCount(VK_REMAINING_ARRAY_LAYERS);

        vkCmdClearDepthStencilImage(commandBuffer, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearValue, range);

        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    @Override
    public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
        checkNotInRenderPass();
        VkCommandBuffer commandBuffer = commandBuffer();

        try (MemoryStack stack = stackPush()) {
            clearColorImage(stack, commandBuffer, ((VkGpuTexture) colorTexture).getVulkanImage(), clearColor);
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
        checkNotInRenderPass();
        VkCommandBuffer commandBuffer = commandBuffer();

        try (MemoryStack stack = stackPush()) {
            clearColorImage(stack, commandBuffer, ((VkGpuTexture) colorTexture).getVulkanImage(), clearColor);
            clearDepthImage(stack, commandBuffer, ((VkGpuTexture) depthTexture).getVulkanImage(), clearDepth);
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth,
                                           int regionX, int regionY, int regionWidth, int regionHeight, int mipLevel) {
        checkNotInRenderPass();
        this.renderer.ensureRecording();

        VulkanImage colorImage = ((VkGpuTexture) colorTexture).getVulkanImage();
        VulkanImage depthImage = ((VkGpuTexture) depthTexture).getVulkanImage();

        int width = colorTexture.getWidth(mipLevel);
        int height = colorTexture.getHeight(mipLevel);

        Framebuffer framebuffer = Framebuffer.builder(colorImage, depthImage)
                                             .setAttachmentViews(colorImage.getLevelImageView(mipLevel), depthImage.getLevelImageView(mipLevel))
                                             .setSize(width, height)
                                             .build();

        net.vulkanmod.vulkan.framebuffer.RenderPass renderPass = getRenderPass(colorImage.format, depthImage.format, false, false);

        net.vulkanmod.vulkan.framebuffer.RenderPass.BeginInfo beginInfo = new net.vulkanmod.vulkan.framebuffer.RenderPass.BeginInfo()
                .setArea(0, 0, width, height);

        this.renderer.beginRenderPass(renderPass, framebuffer, beginInfo);

        try (MemoryStack stack = stackPush()) {
            Renderer.clearAttachments(Renderer.getCommandBuffer(), GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT,
                                      regionX, regionY, regionWidth, regionHeight,
                                      stack.floats(clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w()), (float) clearDepth);
        }

        this.renderer.endRenderPass();
    }

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
        checkNotInRenderPass();
        VkCommandBuffer commandBuffer = commandBuffer();

        try (MemoryStack stack = stackPush()) {
            clearDepthImage(stack, commandBuffer, ((VkGpuTexture) depthTexture).getVulkanImage(), clearDepth);
        }
    }

    // Buffer and texture transfers

    /**
     * Copies data into a staging buffer and returns the staging buffer used; the offset is
     * available through {@link StagingBuffer#getOffset()}.
     */
    private StagingBuffer stage(long size, int alignment, long srcPtr) {
        StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();

        // Use a temporary staging buffer if the upload size is greater than the default one
        if (size > stagingBuffer.getBufferSize()) {
            stagingBuffer = new StagingBuffer(size);
            stagingBuffer.scheduleFree();
        }

        stagingBuffer.align(alignment);
        stagingBuffer.copyBuffer((int) size, srcPtr);

        return stagingBuffer;
    }

    private static void transferBarrier(MemoryStack stack, VkCommandBuffer commandBuffer, boolean beforeTransfer) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.sType$Default();

        if (beforeTransfer) {
            barrier.srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT);

            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                 0, barrier, null, null);
        }
        else {
            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);

            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                                 0, barrier, null, null);
        }
    }

    @Override
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        checkNotInRenderPass();
        VkCommandBuffer commandBuffer = commandBuffer();

        VkGpuBuffer buffer = (VkGpuBuffer) destination.buffer();
        int size = data.remaining();
        if (size == 0) {
            return;
        }

        StagingBuffer stagingBuffer = stage(size, 4, MemoryUtil.memAddress(data));
        long srcOffset = stagingBuffer.getOffset();

        try (MemoryStack stack = stackPush()) {
            transferBarrier(stack, commandBuffer, true);

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.size(size);
            copyRegion.srcOffset(srcOffset);
            copyRegion.dstOffset(destination.offset());

            vkCmdCopyBuffer(commandBuffer, stagingBuffer.getId(), buffer.getId(), copyRegion);

            transferBarrier(stack, commandBuffer, false);
        }
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        checkNotInRenderPass();
        VkCommandBuffer commandBuffer = commandBuffer();

        VkGpuBuffer srcBuffer = (VkGpuBuffer) source.buffer();
        VkGpuBuffer dstBuffer = (VkGpuBuffer) target.buffer();

        try (MemoryStack stack = stackPush()) {
            transferBarrier(stack, commandBuffer, true);

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.size(source.length());
            copyRegion.srcOffset(source.offset());
            copyRegion.dstOffset(target.offset());

            vkCmdCopyBuffer(commandBuffer, srcBuffer.getId(), dstBuffer.getId(), copyRegion);

            transferBarrier(stack, commandBuffer, false);
        }
    }

    private static void copyBufferToImage(MemoryStack stack, VkCommandBuffer commandBuffer, long buffer, long bufferOffset,
                                          int bufferRowLength, int bufferImageHeight, VulkanImage image,
                                          int mipLevel, int layer, int x, int y, int width, int height) {
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(bufferOffset);
        region.bufferRowLength(bufferRowLength);
        region.bufferImageHeight(bufferImageHeight);
        region.imageSubresource().aspectMask(image.aspect);
        region.imageSubresource().mipLevel(mipLevel);
        region.imageSubresource().baseArrayLayer(layer);
        region.imageSubresource().layerCount(1);
        region.imageOffset().set(x, y, 0);
        region.imageExtent().set(width, height, 1);

        vkCmdCopyBufferToImage(commandBuffer, buffer, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    @Override
    public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height) {
        checkNotInRenderPass();
        VkCommandBuffer commandBuffer = commandBuffer();

        VulkanImage image = ((VkGpuTexture) destination).getVulkanImage();
        int blockSize = destination.getFormat().blockSize();
        long size = (long) width * height * blockSize;
        if (size == 0) {
            return;
        }

        StagingBuffer stagingBuffer = stage(size, Math.max(4, blockSize), MemoryUtil.memAddress(source));
        long srcOffset = stagingBuffer.getOffset();

        try (MemoryStack stack = stackPush()) {
            image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            copyBufferToImage(stack, commandBuffer, stagingBuffer.getId(), srcOffset, width, height, image,
                              mipLevel, depthOrLayer, destX, destY, width, height);

            image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    @Override
    public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight,
                                    GpuTexture destination, int destinationX, int destinationY, int copyWidth, int copyHeight,
                                    int mipLevel, int arrayLayer) {
        checkNotInRenderPass();
        VkCommandBuffer commandBuffer = commandBuffer();

        VulkanImage image = ((VkGpuTexture) destination).getVulkanImage();
        VkGpuBuffer buffer = (VkGpuBuffer) source.buffer();
        int blockSize = destination.getFormat().blockSize();

        long bufferOffset = source.offset() + ((long) sourceY * sourceWidth + sourceX) * blockSize;

        try (MemoryStack stack = stackPush()) {
            transferBarrier(stack, commandBuffer, true);
            image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            copyBufferToImage(stack, commandBuffer, buffer.getId(), bufferOffset, sourceWidth, sourceHeight, image,
                              mipLevel, arrayLayer, destinationX, destinationY, copyWidth, copyHeight);

            image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
        this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel,
                                    int x, int y, int width, int height) {
        checkNotInRenderPass();
        VkCommandBuffer commandBuffer = commandBuffer();

        VulkanImage image = ((VkGpuTexture) source).getVulkanImage();
        VkGpuBuffer buffer = (VkGpuBuffer) destination;

        try (MemoryStack stack = stackPush()) {
            int prevLayout = image.getCurrentLayout();
            image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(offset);
            region.bufferRowLength(width);
            region.bufferImageHeight(height);
            region.imageSubresource().aspectMask(image.aspect & ~VK_IMAGE_ASPECT_STENCIL_BIT);
            region.imageSubresource().mipLevel(mipLevel);
            region.imageSubresource().baseArrayLayer(0);
            region.imageSubresource().layerCount(1);
            region.imageOffset().set(x, y, 0);
            region.imageExtent().set(width, height, 1);

            vkCmdCopyImageToBuffer(commandBuffer, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer.getId(), region);

            image.transitionImageLayout(stack, commandBuffer, prevLayout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : prevLayout);

            // Make the copied data visible to the host
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.sType$Default();
            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_HOST_READ_BIT | VK_ACCESS_MEMORY_READ_BIT);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                                 0, barrier, null, null);
        }

        // The callback runs once the frame containing the copy has completed
        RenderSystem.queueFencedTask(callback);
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY,
                                     int sourceX, int sourceY, int width, int height) {
        checkNotInRenderPass();
        VkCommandBuffer commandBuffer = commandBuffer();

        VulkanImage srcImage = ((VkGpuTexture) source).getVulkanImage();
        VulkanImage dstImage = ((VkGpuTexture) destination).getVulkanImage();

        try (MemoryStack stack = stackPush()) {
            srcImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            dstImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().aspectMask(srcImage.aspect).mipLevel(mipLevel).baseArrayLayer(0).layerCount(1);
            region.srcOffset().set(sourceX, sourceY, 0);
            region.dstSubresource().aspectMask(dstImage.aspect).mipLevel(mipLevel).baseArrayLayer(0).layerCount(1);
            region.dstOffset().set(destX, destY, 0);
            region.extent().set(width, height, 1);

            vkCmdCopyImage(commandBuffer, srcImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           dstImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            srcImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            dstImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    @Override
    public GpuFence createFence() {
        checkNotInRenderPass();
        return new VkFence();
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        ((VkQueryPool) pool).writeTimestamp(commandBuffer(), index);
    }

    public VkGpuDevice getDevice() {
        return this.device;
    }

    public void destroy() {
        this.transientMemory.destroy();
    }
}

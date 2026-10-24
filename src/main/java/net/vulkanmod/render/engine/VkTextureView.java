package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.vulkan.VK10;

/**
 * Backend texture view: a Vulkan image view covering a mip range of a {@link VkGpuTexture}.
 */
public class VkTextureView implements GpuTextureView {
    private final VkGpuTexture texture;
    private final int baseMipLevel;
    private final int mipLevels;
    private final long imageView;
    private final boolean ownsView;
    private boolean closed;

    public VkTextureView(VkGpuTexture texture, int baseMipLevel, int mipLevels) {
        this.texture = texture;
        this.baseMipLevel = baseMipLevel;
        this.mipLevels = mipLevels;

        VulkanImage image = texture.getVulkanImage();

        if (baseMipLevel == 0 && mipLevels == image.mipLevels) {
            this.imageView = image.getImageView();
            this.ownsView = false;
        }
        else {
            this.imageView = VulkanImage.createImageView(image.getId(), image.viewType, image.format, image.aspect,
                                                         image.arrayLayers, baseMipLevel, mipLevels);
            this.ownsView = true;
        }

        texture.addView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public VkGpuTexture texture() {
        return this.texture;
    }

    @Override
    public int baseMipLevel() {
        return this.baseMipLevel;
    }

    @Override
    public int mipLevels() {
        return this.mipLevels;
    }

    @Override
    public int getWidth(int mipLevel) {
        return this.texture.getWidth(this.baseMipLevel + mipLevel);
    }

    @Override
    public int getHeight(int mipLevel) {
        return this.texture.getHeight(this.baseMipLevel + mipLevel);
    }

    public long getImageView() {
        return this.imageView;
    }

    public VulkanImage getVulkanImage() {
        return this.texture.getVulkanImage();
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.texture.removeView();

            if (this.ownsView) {
                final long view = this.imageView;
                MemoryManager.getInstance().addFrameOp(() -> VK10.vkDestroyImageView(Vulkan.getVkDevice(), view, null));
            }
        }
    }
}

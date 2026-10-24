package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * Backend texture: wraps a {@link VulkanImage}.
 * <p>
 * Images rest in {@code SHADER_READ_ONLY_OPTIMAL} layout whenever they are not being written to,
 * so they can be bound as samplers at any time without an explicit transition.
 */
public class VkGpuTexture implements GpuTexture {
    private final String label;
    private final int usage;
    private final GpuFormat format;
    private final int width;
    private final int height;
    private final int depthOrLayers;
    private final int mipLevels;
    private final VulkanImage image;
    private final boolean ownsImage;

    private int views;
    private boolean closed;

    public VkGpuTexture(String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels,
                        VulkanImage image, boolean ownsImage) {
        this.label = label;
        this.usage = usage;
        this.format = format;
        this.width = width;
        this.height = height;
        this.depthOrLayers = depthOrLayers;
        this.mipLevels = mipLevels;
        this.image = image;
        this.ownsImage = ownsImage;
    }

    @Override
    public int getWidth(int mipLevel) {
        return Math.max(1, this.width >> mipLevel);
    }

    @Override
    public int getHeight(int mipLevel) {
        return Math.max(1, this.height >> mipLevel);
    }

    @Override
    public int getDepthOrLayers() {
        return this.depthOrLayers;
    }

    @Override
    public int getMipLevels() {
        return this.mipLevels;
    }

    @Override
    public GpuFormat getFormat() {
        return this.format;
    }

    @Override
    public int usage() {
        return this.usage;
    }

    @Override
    public String getLabel() {
        return this.label;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;

            if (this.ownsImage) {
                this.image.free();
            }
        }
    }

    public VulkanImage getVulkanImage() {
        return this.image;
    }

    public int getVkFormat() {
        return this.image.format;
    }

    void addView() {
        this.views++;
    }

    void removeView() {
        this.views--;
    }
}

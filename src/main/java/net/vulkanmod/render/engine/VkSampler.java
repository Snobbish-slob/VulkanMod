package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.vulkanmod.vulkan.texture.SamplerManager;

import java.util.OptionalDouble;

/**
 * Backend sampler. Samplers are shared through {@link SamplerManager}, so closing one does not
 * destroy the Vulkan object.
 */
public class VkSampler implements GpuSampler {
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private boolean closed;

    private final long id;

    public VkSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter,
                     int maxAnisotropy, OptionalDouble maxLod) {
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;

        float vkMaxLod = maxLod.isPresent() ? (float) maxLod.getAsDouble() : 1000.0f;

        this.id = SamplerManager.getSampler(VkConst.of(addressModeU), VkConst.of(addressModeV),
                                            VkConst.of(minFilter), VkConst.of(magFilter),
                                            VkConst.mipmapMode(minFilter), vkMaxLod,
                                            maxAnisotropy > 1, maxAnisotropy, -1);
    }

    public long getId() {
        return this.id;
    }

    @Override
    public AddressMode getAddressModeU() {
        return this.addressModeU;
    }

    @Override
    public AddressMode getAddressModeV() {
        return this.addressModeV;
    }

    @Override
    public FilterMode getMinFilter() {
        return this.minFilter;
    }

    @Override
    public FilterMode getMagFilter() {
        return this.magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return this.maxAnisotropy;
    }

    @Override
    public OptionalDouble getMaxLod() {
        return this.maxLod;
    }

    @Override
    public void close() {
        this.closed = true;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }
}

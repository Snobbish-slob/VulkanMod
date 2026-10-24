package net.vulkanmod.mixin.render.backend;

import com.mojang.renderpearl.api.device.GpuBackend;
import net.minecraft.client.PreferredGraphicsApi;
import net.vulkanmod.render.engine.VkBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiM {

    /**
     * @author VulkanMod
     * @reason The game is always rendered through VulkanMod's backend, regardless of the
     * "Graphics API" option.
     */
    @Overwrite
    public GpuBackend[] getBackendsToTry() {
        return new GpuBackend[]{new VkBackend()};
    }
}

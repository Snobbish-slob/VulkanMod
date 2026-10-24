package net.vulkanmod.mixin.render.vertex;

import com.mojang.renderpearl.api.pipeline.IndexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(IndexType.class)
public class IndexTypeMixin {

    /**
     * @author
     * @reason VulkanMod always uses 16-bit indices for its own index buffers
     */
    @Overwrite
    public static IndexType least(int number) {
        return IndexType.SHORT;
    }
}

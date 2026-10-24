package net.vulkanmod.mixin.render.frapi;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ItemFeatureRenderer.class)
public interface ItemFeatureRendererAccessor {
    @Invoker("computeFoilDecalPose")
    static PoseStack.Pose vulkanmod$computeFoilDecalPose(ItemDisplayContext type, PoseStack.Pose pose) {
        throw new AssertionError();
    }
}

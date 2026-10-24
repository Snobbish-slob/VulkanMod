package net.vulkanmod.mixin.profiling;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.vulkanmod.render.profiling.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "submitFeatures",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/LevelRenderer;submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
    private void profilerEntities(LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector, boolean renderOutline, CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("entities");
    }

    @Inject(method = "submitFeatures", at = @At("RETURN"))
    private void profilerEntitiesEnd(LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector, boolean renderOutline, CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
    }
}

package net.vulkanmod.mixin.render;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod uses an infinite far plane (reverse depth), so the perspective far distance is not limited.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setupPerspective(FFFFF)V"), index = 1)
    private float infiniteDepthFar(float zFar) {
        return Float.POSITIVE_INFINITY;
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void infiniteDepthFarState(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
        cameraState.depthFar = Float.POSITIVE_INFINITY;
    }
}

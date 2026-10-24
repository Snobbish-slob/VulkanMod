package net.vulkanmod.mixin.profiling;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.vulkanmod.render.profiling.ProfilerOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the profiler overlay where the game extracts its own debug overlay, which gives the
 * extractor as a parameter instead of requiring a local capture.
 */
@Mixin(Hud.class)
public class HudMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "extractDebugOverlay", at = @At("HEAD"))
    private void renderProfilerOverlay(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (ProfilerOverlay.shouldRender && ProfilerOverlay.INSTANCE != null && !this.minecraft.getDebugOverlay().showDebugScreen())
            ProfilerOverlay.INSTANCE.render(graphics);
    }
}

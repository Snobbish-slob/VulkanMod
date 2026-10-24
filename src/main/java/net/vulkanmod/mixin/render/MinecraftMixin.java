package net.vulkanmod.mixin.render;

import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.main.GameConfig;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Shadow @Final public Options options;

    @Inject(method = "<init>", at = @At(value = "RETURN"))
    private void forceGraphicsMode(GameConfig gameConfig, CallbackInfo ci) {
        var graphicsModeOption = this.options.graphicsPreset();

        if (graphicsModeOption.get() == GraphicsPreset.FABULOUS) {
            Initializer.LOGGER.error("Fabulous graphics mode not supported, forcing Fancy.");
            graphicsModeOption.set(GraphicsPreset.FANCY);
        }

        if (this.options.improvedTransparency().get()) {
            Initializer.LOGGER.error("Improved transparency currently not supported, forcing it off.");
            this.options.improvedTransparency().set(false);
        }
    }

}

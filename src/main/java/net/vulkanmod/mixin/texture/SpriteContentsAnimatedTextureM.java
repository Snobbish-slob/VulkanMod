package net.vulkanmod.mixin.texture;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(SpriteContents.AnimatedTexture.class)
public class SpriteContentsAnimatedTextureM {

    @ModifyArg(method = "createAnimationState", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;"), index = 6)
    private int fixMipLevels(int mipLevels) {
        return mipLevels - 1;
    }
}

package net.vulkanmod.mixin.render.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.vulkan.Renderer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Shadow public boolean noRender;

    @Inject(method = "runTick", at = @At(value = "HEAD"))
    private void preFrameOps(boolean bl, CallbackInfo ci) {
//        Renderer.getInstance().preInitFrame();
        Renderer.getInstance().beginFrame();

        Renderer.clearAttachments(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;ILcom/mojang/renderpearl/api/textures/GpuTexture;D)V"))
    private void beginRender(CommandEncoder instance, GpuTexture gpuTexture, int i, GpuTexture gpuTexture2, double v) {
        // Remove framebuffer clear

        ImageUploadHelper.INSTANCE.submitCommands();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At(value = "RETURN"))
    private void beginRender2(CallbackInfo ci) {
//        Renderer.getInstance().beginFrame();
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen()V"))
    private void removeBlit(RenderTarget instance) {
    }


    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V"))
    private void removeThreadYield() {
    }

}

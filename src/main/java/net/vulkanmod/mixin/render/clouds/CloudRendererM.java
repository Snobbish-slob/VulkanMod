package net.vulkanmod.mixin.render.clouds;

import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.profiling.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Replaces the vanilla cloud mesh with VulkanMod's cloud renderer. The vanilla renderer still
 * owns the cloud texture reload; VulkanMod reloads its own copy alongside it.
 */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererM {
    @Unique private net.vulkanmod.render.sky.CloudRenderer vmCloudRenderer;

    @Unique private int vmCloudColor;
    @Unique private float vmCloudHeight;
    @Unique private Vec3 vmCameraPos = Vec3.ZERO;
    @Unique private long vmGameTime;
    @Unique private float vmPartialTicks;

    @Inject(method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V", at = @At("HEAD"), cancellable = true)
    private void capturePrepare(int color, CloudStatus cloudStatus, float bottomY, int range, Vec3 cameraPosition,
                                long gameTime, float partialTicks, CallbackInfo ci) {
        this.vmCloudColor = color;
        this.vmCloudHeight = bottomY;
        this.vmCameraPos = cameraPosition;
        this.vmGameTime = gameTime;
        this.vmPartialTicks = partialTicks;
        ci.cancel();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void renderClouds(CloudStatus cloudStatus, RenderPass renderPass, CallbackInfo ci) {
        if (this.vmCloudRenderer == null) {
            this.vmCloudRenderer = new net.vulkanmod.render.sky.CloudRenderer();
        }

        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Clouds");
        this.vmCloudRenderer.renderClouds(this.vmCloudHeight, this.vmCloudColor,
                                          this.vmCameraPos.x(), this.vmCameraPos.y(), this.vmCameraPos.z(),
                                          this.vmGameTime, this.vmPartialTicks);
        profiler.pop();

        ci.cancel();
    }

    @Inject(method = "apply(Ljava/util/Optional;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("RETURN"))
    private void onReload(Optional<CloudRenderer.TextureData> preparations, ResourceManager manager, ProfilerFiller profiler, CallbackInfo ci) {
        if (this.vmCloudRenderer != null) {
            this.vmCloudRenderer.loadTexture();
            this.vmCloudRenderer.resetBuffer();
        }
    }
}

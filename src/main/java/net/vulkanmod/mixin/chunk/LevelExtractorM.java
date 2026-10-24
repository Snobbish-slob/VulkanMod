package net.vulkanmod.mixin.chunk;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Game-thread side of the chunk renderer replacement: keeps VulkanMod's section grid in sync
 * with the level and runs the section graph update where the game would apply its frustum.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorM {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private @Nullable ClientLevel level;

    @Inject(method = "setLevel", at = @At("RETURN"))
    private void setLevel(ClientLevel level, CallbackInfo ci) {
        WorldRenderer worldRenderer = WorldRenderer.getInstance();
        if (worldRenderer != null) {
            worldRenderer.setLevel(level);
        }
    }

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void allChanged(CallbackInfo ci) {
        WorldRenderer worldRenderer = WorldRenderer.getInstance();
        if (worldRenderer != null && this.level != null) {
            worldRenderer.allChanged();
        }
    }

    @Inject(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/level/LevelRenderState;reset()V"))
    private void setupRenderer(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks, CallbackInfo ci) {
        WorldRenderer worldRenderer = WorldRenderer.getInstance();
        if (worldRenderer == null || this.level == null) {
            return;
        }

        Frustum capturedFrustum = camera.getCapturedFrustum();
        Frustum frustum = capturedFrustum != null ? capturedFrustum : camera.getCullFrustum();
        boolean spectator = this.minecraft.player != null && this.minecraft.player.isSpectator();

        worldRenderer.setupRenderer(camera, frustum, capturedFrustum != null, spectator);
    }

    /**
     * @author VulkanMod
     * @reason Block entities are collected from VulkanMod's compiled sections.
     */
    @Overwrite
    private void extractVisibleBlockEntities(Camera camera, float deltaPartialTick, LevelRenderState levelRenderState) {
        WorldRenderer worldRenderer = WorldRenderer.getInstance();
        if (worldRenderer == null || this.level == null) {
            return;
        }

        worldRenderer.extractBlockEntities(camera, deltaPartialTick, levelRenderState, this.level.destructionProgress());
    }

    /**
     * @author VulkanMod
     * @reason The vanilla occlusion graph has no view area.
     */
    @Overwrite
    private void applyFrustum(Frustum frustum) {
    }

    /**
     * @author VulkanMod
     * @reason Dirty sections are tracked by VulkanMod's section grid.
     */
    @Overwrite
    private void setSectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged) {
        WorldRenderer worldRenderer = WorldRenderer.getInstance();
        if (worldRenderer != null && worldRenderer.getSectionGrid() != null) {
            worldRenderer.setSectionDirty(sectionX, sectionY, sectionZ, playerChanged);
        }
    }

    /**
     * @author VulkanMod
     * @reason Statistics come from VulkanMod's section graph.
     */
    @Overwrite
    public int countRenderedSections() {
        WorldRenderer worldRenderer = WorldRenderer.getInstance();
        return worldRenderer != null && worldRenderer.getSectionGrid() != null ? worldRenderer.getVisibleSectionsCount() : 0;
    }

    /**
     * @author VulkanMod
     * @reason Statistics come from VulkanMod's section graph.
     */
    @Overwrite
    public @Nullable String sectionStatistics() {
        WorldRenderer worldRenderer = WorldRenderer.getInstance();
        return worldRenderer != null ? worldRenderer.getChunkStatistics() : null;
    }
}

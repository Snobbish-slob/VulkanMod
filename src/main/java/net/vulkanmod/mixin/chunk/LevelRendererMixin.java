package net.vulkanmod.mixin.chunk;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.Options;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla section renderer with VulkanMod's chunk renderer.
 * <p>
 * Since 26.3 the game-thread side (dirty sections, frustum, block entity extraction) lives in
 * {@code LevelExtractor} and is hooked by {@link LevelExtractorM}; this mixin covers the render side.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow @Final private GameRenderer gameRenderer;

    @Shadow public abstract net.minecraft.client.renderer.CloudRenderer cloudRenderer();

    @Shadow public abstract void clearVisibleSections();

    @Unique private WorldRenderer worldRenderer;

    @Unique private final Matrix4f modelView = new Matrix4f();
    @Unique private final Matrix4f projection = new Matrix4f();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(EntityRenderDispatcher entityRenderDispatcher, BlockEntityRenderDispatcher blockEntityRenderDispatcher,
                      ModelManager modelManager, TextureManager textureManager, AtlasManager atlasManager,
                      ShaderManager shaderManager, GameRenderer gameRenderer, int width, int height, CallbackInfo ci) {
        this.worldRenderer = WorldRenderer.init(entityRenderDispatcher, blockEntityRenderDispatcher, gameRenderer.renderBuffers(),
                                                gameRenderer.gameRenderState().levelRenderState, gameRenderer.featureRenderDispatcher());
    }

    /**
     * @author VulkanMod
     * @reason The vanilla view area and section dispatcher are never created; VulkanMod keeps
     * its own section grid (rebuilt from {@code LevelExtractor.allChanged}).
     */
    @Overwrite
    public void invalidateCompiledGeometry(ClientLevel level, Options options, Camera camera, BlockColors blockColors) {
        this.cloudRenderer().markForRebuild();
        LeavesBlock.setCutoutLeaves(options.cutoutLeaves().get());
        this.clearVisibleSections();
    }

    /**
     * @author VulkanMod
     * @reason No vanilla section data to release.
     */
    @Overwrite
    public void resetLevelRenderData() {
        this.clearVisibleSections();
    }

    /**
     * @author VulkanMod
     * @reason Terrain draws are issued directly by VulkanMod in {@link #renderSectionLayer}.
     */
    @Overwrite
    public ChunkSectionsToRender prepareChunkRenders(Matrix4fc modelViewMatrix, boolean respectTranslucentOrder) {
        this.modelView.set(modelViewMatrix);
        this.projection.set(this.levelRenderState.cameraRenderState.projectionMatrix);
        return null;
    }

    /**
     * @author VulkanMod
     * @reason Terrain draws are issued directly by VulkanMod in {@link #renderSectionLayer}.
     */
    @Overwrite
    public ChunkSectionsToRender prepareChunkRendersIndirect(Matrix4fc modelViewMatrix, boolean respectTranslucentOrder) {
        return this.prepareChunkRenders(modelViewMatrix, respectTranslucentOrder);
    }

    @Redirect(method = {"executeSolid", "executeClassicTransparency"},
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"))
    private void renderSectionLayer(ChunkSectionsToRender instance, ChunkSectionLayerGroup group, RenderPass renderPass,
                                    GpuSampler sampler, GpuTextureView atlas, boolean wireframe) {
        Vec3 cameraPos = this.levelRenderState.cameraRenderState.pos;
        double camX = cameraPos.x();
        double camY = cameraPos.y();
        double camZ = cameraPos.z();

        Profiler profiler = Profiler.getMainProfiler();

        if (group == ChunkSectionLayerGroup.OPAQUE) {
            profiler.push("Opaque_terrain");

            this.worldRenderer.renderSectionLayer(TerrainRenderType.SOLID, camX, camY, camZ, this.modelView, this.projection);
            this.worldRenderer.renderSectionLayer(TerrainRenderType.CUTOUT, camX, camY, camZ, this.modelView, this.projection);

            profiler.pop();
        }
        else if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
            profiler.push("Translucent_terrain");

            this.worldRenderer.renderSectionLayer(TerrainRenderType.TRANSLUCENT, camX, camY, camZ, this.modelView, this.projection);

            profiler.pop();
        }
    }

    /**
     * @author VulkanMod
     * @reason Sections are compiled by VulkanMod's task dispatcher.
     */
    @Overwrite
    private void compileSections(CameraRenderState camera) {
    }

    @Redirect(method = "render",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V"))
    private void skipOcclusionGraphUpdate(SectionOcclusionGraph instance, CameraRenderState camera, int fov, ChunkLoadingRenderState chunkLoadingRenderState) {
        // The vanilla occlusion graph has no view area; VulkanMod's section graph is updated in LevelExtractorM
    }

    /**
     * @author VulkanMod
     * @reason Visibility comes from VulkanMod's section grid.
     */
    @Overwrite
    public boolean isSectionCompiledAndVisible(BlockPos blockPos, long chunkFadeDuration) {
        return this.worldRenderer.isSectionCompiled(blockPos);
    }

    /**
     * @author VulkanMod
     * @reason Completion state comes from VulkanMod's task dispatcher.
     */
    @Overwrite
    public boolean hasRenderedAllSections() {
        return !this.worldRenderer.graphNeedsUpdate() && this.worldRenderer.getTaskDispatcher().isIdle();
    }
}

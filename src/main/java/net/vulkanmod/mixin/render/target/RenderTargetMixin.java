package net.vulkanmod.mixin.render.target;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.RenderPipelines;
import net.vulkanmod.render.engine.VkFbo;
import net.vulkanmod.render.engine.VkGpuTexture;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;

import java.util.Optional;

@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {

    @Shadow public int width;
    @Shadow public int height;

    @Shadow @Nullable protected GpuTexture colorTexture;
    @Shadow @Nullable protected GpuTexture depthTexture;
    @Shadow @Nullable protected GpuTextureView colorTextureView;

    @Overwrite
    public void blitAndBlendToTexture(GpuTextureView gpuTextureView, GpuTextureView outputDepth) {
        RenderSystem.assertOnRenderThread();

        VkFbo fbo = ((VkGpuTexture) this.colorTexture).getFbo(this.depthTexture);
        if (fbo.needsClear()) {
            return;
        }

        try (RenderPass renderPass = RenderSystem.getDevice()
                                                 .createCommandEncoder()
                                                 .createRenderPass(() -> "Blit render target", gpuTextureView, Optional.empty())) {
            renderPass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.ENTITY_OUTLINE_BLIT));
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("InSampler", this.colorTextureView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.draw(3, 1, 0, 0);
        }
    }

}

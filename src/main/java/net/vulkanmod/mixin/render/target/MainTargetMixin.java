package net.vulkanmod.mixin.render.target;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.api.GpuFormat;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(MainTarget.class)
public class MainTargetMixin extends RenderTarget {

    public MainTargetMixin(String label, GpuFormat colorFormat, GpuFormat depthFormat) {
        super(label, colorFormat, depthFormat);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private void createFrameBuffer(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void createBuffers(int i, int j) {
        RenderSystem.assertOnRenderThread();
        GpuDevice gpuDevice = RenderSystem.getDevice();
        int k = gpuDevice.getDeviceInfo().limits().maxTextureSize();
        if (i > 0 && i <= k && j > 0 && j <= k) {
            this.width = i;
            this.height = j;
            if (this.depthFormat != null) {
                this.depthTexture = gpuDevice.createTexture(() -> this.label + " / Depth", 15, this.depthFormat, i, j, 1, 1);
                this.depthTextureView = gpuDevice.createTextureView(this.depthTexture);
            }

            this.colorTexture = gpuDevice.createTexture(() -> this.label + " / Color", 15, this.colorFormat, i, j, 1, 1);
            this.colorTextureView = gpuDevice.createTextureView(this.colorTexture);
        } else {
            throw new IllegalArgumentException("Window " + i + "x" + j + " size out of bounds (max. size: " + k + ")");
        }
    }

    @Override
    public GpuTexture getColorTexture() {
        return Renderer.getInstance().getMainPass().getColorAttachment();
    }

    @Override
    public GpuTextureView getColorTextureView() {
        return Renderer.getInstance().getMainPass().getColorAttachmentView();
    }

    @Override
    public GpuTexture getDepthTexture() {
        return Renderer.getInstance().getMainPass().getDepthAttachment();
    }
}

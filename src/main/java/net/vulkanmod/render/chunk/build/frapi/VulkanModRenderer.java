package net.vulkanmod.render.chunk.build.frapi;

import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer;
import net.minecraft.client.color.block.BlockColors;
import net.vulkanmod.render.chunk.build.frapi.mesh.EncodingFormat;
import net.vulkanmod.render.chunk.build.frapi.mesh.MutableMeshImpl;
import net.vulkanmod.render.chunk.build.frapi.mesh.MutableQuadViewImpl;
import net.vulkanmod.render.chunk.build.frapi.render.AltModelBlockRendererImpl;

import java.util.function.Consumer;

/**
 * Fabric renderer implementation.
 */
public class VulkanModRenderer implements Renderer {
	public static final VulkanModRenderer INSTANCE = new VulkanModRenderer();

	private VulkanModRenderer() {}

	@Override
	public QuadEmitter quadEmitter(Consumer<? super MutableQuadView> consumer) {
		return new MutableQuadViewImpl() {
			{
				data = new int[EncodingFormat.TOTAL_STRIDE];
				clear();
			}

			@Override
			protected void emitDirectly() {
				consumer.accept(this);
			}
		};
	}

	@Override
	public MutableMesh mutableMesh() {
		return new MutableMeshImpl();
	}

	@Override
	public AltModelBlockRenderer altModelBlockRenderer(boolean ambientOcclusion, boolean cull, BlockColors blockColors) {
		return new AltModelBlockRendererImpl(ambientOcclusion, cull, blockColors);
	}
}

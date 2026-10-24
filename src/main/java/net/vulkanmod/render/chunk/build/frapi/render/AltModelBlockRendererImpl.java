/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.vulkanmod.render.chunk.build.frapi.render;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.build.light.LightMode;
import net.vulkanmod.render.chunk.build.frapi.mesh.MutableQuadViewImpl;
import net.vulkanmod.render.chunk.build.light.LightPipeline;
import net.vulkanmod.render.chunk.build.light.data.ArrayLightDataCache;
import net.vulkanmod.render.chunk.build.light.flat.FlatLightPipeline;
import net.vulkanmod.render.chunk.build.light.smooth.NewSmoothLightPipeline;
import net.vulkanmod.render.chunk.build.light.smooth.SmoothLightPipeline;

/**
 * Renders a block model outside of terrain (moving blocks, block entities, items held as blocks)
 * with VulkanMod's light pipelines, emitting the shaded quads to the caller's emitter.
 */
public class AltModelBlockRendererImpl extends AbstractBlockRenderContext implements AltModelBlockRenderer {
	private final boolean ambientOcclusion;
	private final boolean cull;
	private final ArrayLightDataCache lightDataCache = new ArrayLightDataCache();

	private QuadEmitter output;
	private float offsetX, offsetY, offsetZ;

	public AltModelBlockRendererImpl(boolean ambientOcclusion, boolean cull, BlockColors blockColors) {
		super(blockColors);
		this.ambientOcclusion = ambientOcclusion;
		this.cull = cull;

		LightPipeline flatLightPipeline = new FlatLightPipeline(this.lightDataCache);
		LightPipeline smoothLightPipeline;
		if (Initializer.CONFIG.ambientOcclusion == LightMode.SUB_BLOCK) {
			smoothLightPipeline = new NewSmoothLightPipeline(this.lightDataCache);
		} else {
			smoothLightPipeline = new SmoothLightPipeline(this.lightDataCache);
		}
		this.setupLightPipelines(flatLightPipeline, smoothLightPipeline);
		this.random = RandomSource.create();
	}

	@Override
	public void tesselateBlock(QuadEmitter output, float x, float y, float z, BlockAndTintGetter level, BlockPos pos,
							   BlockState blockState, BlockStateModel model, long seed) {
		Vec3 offset = blockState.getOffset(pos);
		this.offsetX = x + (float) offset.x;
		this.offsetY = y + (float) offset.y;
		this.offsetZ = z + (float) offset.z;
		this.output = output;

		this.random.setSeed(seed);
		this.lightDataCache.reset(level, pos);
		this.prepareForWorld(level, this.cull);
		this.prepareForBlock(blockState, pos, this.ambientOcclusion && blockState.getLightEmission() == 0);

		try {
			model.emitQuads(this.getEmitter(), level, pos, blockState, this.random, this::isFaceCulled);
		} finally {
			this.output = null;
			this.renderRegion = null;
		}
	}

	@Override
	protected void endRenderQuad(MutableQuadViewImpl quad) {
		final TriState aoMode = quad.ambientOcclusion();
		final boolean ao = this.useAO && this.ambientOcclusion && (aoMode == TriState.TRUE || (aoMode == TriState.DEFAULT && this.defaultAO));
		final boolean emissive = quad.emissive();
		final boolean vanillaShade = quad.shadeMode() == ShadeMode.VANILLA;

		LightPipeline lightPipeline = ao ? this.smoothLightPipeline : this.flatLightPipeline;

		tintQuad(quad);
		shadeQuad(quad, lightPipeline, emissive, vanillaShade);

		for (int i = 0; i < 4; i++) {
			quad.lightmap(i, this.quadLightData.lm[i]);
		}

		quad.translate(this.offsetX, this.offsetY, this.offsetZ);
		this.output.copyFrom(quad).emit();
	}
}

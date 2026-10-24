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

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2ByteLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.vulkanmod.render.chunk.build.frapi.helper.ColorHelper;
import net.vulkanmod.render.chunk.build.frapi.mesh.EncodingFormat;
import net.vulkanmod.render.chunk.build.frapi.mesh.MutableQuadViewImpl;
import net.vulkanmod.render.chunk.build.light.LightPipeline;
import net.vulkanmod.render.chunk.build.light.data.QuadLightData;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public abstract class AbstractBlockRenderContext extends AbstractRenderContext {
	private final MutableQuadViewImpl editorQuad = new MutableQuadViewImpl() {
		{
			data = new int[EncodingFormat.TOTAL_STRIDE];
			clear();
		}

		@Override
		public void emitDirectly() {
			renderQuad(this);
		}
	};

	protected BlockState blockState;
	protected BlockPos blockPos;
	protected BlockPos.MutableBlockPos tempPos = new BlockPos.MutableBlockPos();

	protected BlockAndTintGetter renderRegion;

	protected final Object2ByteLinkedOpenHashMap<ShapePairKey> occlusionCache = new Object2ByteLinkedOpenHashMap<>(2048, 0.25F) {
		protected void rehash(int i) {
		}
	};

	protected final QuadLightData quadLightData = new QuadLightData();
	protected LightPipeline smoothLightPipeline;
	protected LightPipeline flatLightPipeline;

	protected boolean useAO;
	protected boolean defaultAO;

	protected RandomSource random;

	protected boolean enableCulling = true;
	protected int cullCompletionFlags;
	protected int cullResultFlags;

	// Block tint sources (26.3 replaced BlockColor callbacks with BlockTintSource lists)
	private final BlockColors blockColors;
	private final List<@Nullable BlockTintSource> tintSources = new ObjectArrayList<>();
	private final IntList computedTintValues = new IntArrayList();
	private boolean tintSourcesInitialized;
	private int tintCacheIndex = -1;
	private int tintCacheValue;

	protected AbstractBlockRenderContext() {
		this(Minecraft.getInstance().getBlockColors());
	}

	protected AbstractBlockRenderContext(BlockColors blockColors) {
		this.occlusionCache.defaultReturnValue((byte) 127);
		this.blockColors = blockColors;
	}

	protected void setupLightPipelines(LightPipeline flatLightPipeline, LightPipeline smoothLightPipeline) {
		this.flatLightPipeline = flatLightPipeline;
		this.smoothLightPipeline = smoothLightPipeline;
	}

	public void prepareForWorld(BlockAndTintGetter blockView, boolean enableCulling) {
		this.renderRegion = blockView;
		this.enableCulling = enableCulling;
	}

	public void prepareForBlock(BlockState blockState, BlockPos blockPos, boolean modelAo) {
		this.blockPos = blockPos;
		this.blockState = blockState;

		this.useAO = Minecraft.getInstance().options.ambientOcclusion().get();
		this.defaultAO = this.useAO && modelAo && blockState.getLightEmission() == 0;

		this.cullCompletionFlags = 0;
		this.cullResultFlags = 0;

		this.resetTintCache();
	}

	public boolean isFaceCulled(@Nullable Direction face) {
		return !this.shouldRenderFace(face);
	}

	public boolean shouldRenderFace(Direction face) {
		if (face == null || !enableCulling) {
			return true;
		}

		final int mask = 1 << face.get3DDataValue();

		if ((cullCompletionFlags & mask) == 0) {
			cullCompletionFlags |= mask;

			if (this.faceNotOccluded(blockState, face)) {
				cullResultFlags |= mask;
				return true;
			} else {
				return false;
			}
		} else {
			return (cullResultFlags & mask) != 0;
		}
	}

	public boolean faceNotOccluded(BlockState blockState, Direction face) {
		BlockGetter blockGetter = this.renderRegion;
		BlockPos adjPos = tempPos.setWithOffset(blockPos, face);
		BlockState adjBlockState = blockGetter.getBlockState(adjPos);

		if (blockState.skipRendering(adjBlockState, face)) {
			return false;
		}

		if (adjBlockState.canOcclude()) {
			VoxelShape shape = blockState.getFaceOcclusionShape(face);

			if (shape.isEmpty())
				return true;

			VoxelShape adjShape = adjBlockState.getFaceOcclusionShape(face.getOpposite());

			if (adjShape.isEmpty())
				return true;

			if (shape == Shapes.block() && adjShape == Shapes.block()) {
				return false;
			}

			ShapePairKey blockStatePairKey = new ShapePairKey(shape, adjShape);

			byte b = occlusionCache.getAndMoveToFirst(blockStatePairKey);
			if (b != 127) {
				return b != 0;
			} else {
				boolean bl = Shapes.joinIsNotEmpty(shape, adjShape, BooleanOp.ONLY_FIRST);
				if (occlusionCache.size() == 2048) {
					occlusionCache.removeLastByte();
				}

				occlusionCache.putAndMoveToFirst(blockStatePairKey, (byte) (bl ? 1 : 0));
				return bl;
			}
		}

		return true;
	}

	public QuadEmitter getEmitter() {
		editorQuad.clear();
		return editorQuad;
	}

	@Override
	protected void bufferQuad(MutableQuadViewImpl quadView) {
		this.renderQuad(quadView);
	}

	private void renderQuad(MutableQuadViewImpl quad) {
		if (isFaceCulled(quad.cullFace())) {
			return;
		}

		endRenderQuad(quad);
	}

	protected void endRenderQuad(MutableQuadViewImpl quad) {}

	/** handles block color, common to all renders. */
	protected void tintQuad(MutableQuadViewImpl quad) {
		int tintIndex = quad.tintIndex();

		if (tintIndex != -1) {
			final int blockColor = 0xFF000000 | getTintColor(tintIndex);

			for (int i = 0; i < 4; i++) {
				quad.color(i, ColorHelper.multiplyColor(blockColor, quad.color(i)));
			}
		}
	}

	private void configureTintCache() {
		List<BlockTintSource> sources = this.blockColors.getTintSources(this.blockState);
		int count = sources.size();

		if (count > 0) {
			this.tintSources.addAll(sources);

			for (int i = 0; i < count; ++i) {
				this.computedTintValues.add(-1);
			}
		}
	}

	private int getTintColor(int tintIndex) {
		if (this.tintCacheIndex == tintIndex) {
			return this.tintCacheValue;
		}

		if (!this.tintSourcesInitialized) {
			this.configureTintCache();
			this.tintSourcesInitialized = true;
		}

		int color;
		if (tintIndex >= this.tintSources.size()) {
			color = -1;
		} else {
			BlockTintSource tintSource = this.tintSources.set(tintIndex, null);

			if (tintSource != null) {
				color = tintSource.colorInWorld(this.blockState, this.renderRegion, this.blockPos);
				this.computedTintValues.set(tintIndex, color);
			} else {
				color = this.computedTintValues.getInt(tintIndex);
			}
		}

		this.tintCacheIndex = tintIndex;
		this.tintCacheValue = color;
		return color;
	}

	private void resetTintCache() {
		this.tintCacheIndex = -1;

		if (this.tintSourcesInitialized) {
			this.tintSources.clear();
			this.computedTintValues.clear();
			this.tintSourcesInitialized = false;
		}
	}

	protected void shadeQuad(MutableQuadViewImpl quad, LightPipeline lightPipeline, boolean emissive, boolean vanillaShade) {
		QuadLightData data = this.quadLightData;

		// Light pipelines compute light and ambient occlusion; the directional brightness is applied here
		// so that a shade direction override can replace the quad's own face.
		lightPipeline.calculate(quad, this.blockPos, data, quad.cullFace(), quad.lightFace(), false);

		Direction shadeDirection = quad.shadeDirectionOverride();
		float directionalBrightness = this.renderRegion.cardinalLighting().byFace(shadeDirection != null ? shadeDirection : quad.lightFace());

		if (emissive) {
			for (int i = 0; i < 4; i++) {
				quad.color(i, ColorHelper.multiplyRGB(quad.color(i), data.br[i] * directionalBrightness));
				data.lm[i] = LightCoordsUtil.FULL_BRIGHT;
			}
		} else {
			for (int i = 0; i < 4; i++) {
				quad.color(i, ColorHelper.multiplyRGB(quad.color(i), data.br[i] * directionalBrightness));
				data.lm[i] = ColorHelper.maxBrightness(quad.lightmap(i), data.lm[i]);
			}
		}
	}

	// TODO move elsewhere
	record ShapePairKey(VoxelShape first, VoxelShape second) {
		public boolean equals(Object object) {
			if (object instanceof ShapePairKey shapePairKey && this.first == shapePairKey.first && this.second == shapePairKey.second) {
				return true;
			}
			return false;
		}

		public int hashCode() {
			return System.identityHashCode(this.first) * 31 + System.identityHashCode(this.second);
		}
	}
}

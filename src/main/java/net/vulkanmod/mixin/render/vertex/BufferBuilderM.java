package net.vulkanmod.mixin.render.vertex;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.vulkanmod.interfaces.ExtendedVertexBuilder;
import net.vulkanmod.render.vertex.format.I32_SNorm;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderM
        implements VertexConsumer, ExtendedVertexBuilder {
    // Semantic IDs used by BufferBuilder.beginElement
    private static final int COLOR_SEMANTIC_ID = 1;
    private static final int UV0_SEMANTIC_ID = 2;
    private static final int UV1_SEMANTIC_ID = 3;
    private static final int UV2_SEMANTIC_ID = 4;
    private static final int NORMAL_SEMANTIC_ID = 6;

    @Shadow @Final private boolean blockFormat;
    @Shadow @Final private boolean entityFormat;

    @Shadow protected abstract long beginVertex();
    @Shadow private int elementsToFill;
    @Shadow @Final private int initialElementsToFill;

    @Shadow protected abstract long beginElement(int semanticID);

    private long ptr;

    public void vertex(float x, float y, float z, int packedColor, float u, float v, int overlay, int light, int packedNormal) {
        this.ptr = this.beginVertex();

        if (this.entityFormat) {
            MemoryUtil.memPutFloat(ptr + 0, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);
            MemoryUtil.memPutInt(ptr + 12, packedColor);
            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);
            MemoryUtil.memPutInt(ptr + 24, overlay);
            MemoryUtil.memPutInt(ptr + 28, light);
            MemoryUtil.memPutInt(ptr + 32, packedNormal);
        }
        else {
            this.elementsToFill = this.initialElementsToFill;
            this.position(x, y, z);
            this.fastColor(packedColor);
            this.fastUv(u, v);
            this.fastOverlay(overlay);
            this.light(light);
            this.fastNormal(packedNormal);
        }
    }

    public void vertex(float x, float y, float z, float u, float v, int packedColor, int light) {
        this.ptr = this.beginVertex();

        MemoryUtil.memPutFloat(ptr + 0, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);
        MemoryUtil.memPutFloat(ptr + 12, u);
        MemoryUtil.memPutFloat(ptr + 16, v);
        MemoryUtil.memPutInt(ptr + 20, packedColor);
        MemoryUtil.memPutInt(ptr + 24, light);
    }

    public void position(float x, float y, float z) {
        MemoryUtil.memPutFloat(ptr + 0, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);
    }

    public void fastColor(int packedColor) {
        long ptr = this.beginElement(COLOR_SEMANTIC_ID);
        if (ptr != -1L) {
            MemoryUtil.memPutInt(ptr, packedColor);
        }
    }

    public void fastUv(float u, float v) {
        long ptr = this.beginElement(UV0_SEMANTIC_ID);
        if (ptr != -1L) {
            MemoryUtil.memPutFloat(ptr, u);
            MemoryUtil.memPutFloat(ptr + 4, v);
        }
    }

    public void fastOverlay(int o) {
        long ptr = this.beginElement(UV1_SEMANTIC_ID);
        if (ptr != -1L) {
            MemoryUtil.memPutInt(ptr, o);
        }
    }

    public void light(int l) {
        long ptr = this.beginElement(UV2_SEMANTIC_ID);
        if (ptr != -1L) {
            MemoryUtil.memPutInt(ptr, l);
        }
    }

    public void fastNormal(int packedNormal) {
        long ptr = this.beginElement(NORMAL_SEMANTIC_ID);
        if (ptr != -1L) {
            MemoryUtil.memPutInt(ptr, packedNormal);
        }
    }

    /**
     * @author
     * @reason Fast path writing the full vertex at once for the block and entity formats
     */
    @Overwrite
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
        if (this.blockFormat) {
            long ptr = this.beginVertex();
            MemoryUtil.memPutFloat(ptr + 0, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);
            MemoryUtil.memPutInt(ptr + 12, color);
            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);
            MemoryUtil.memPutInt(ptr + 24, light);
        } else if (this.entityFormat) {
            long ptr = this.beginVertex();
            MemoryUtil.memPutFloat(ptr + 0, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);
            MemoryUtil.memPutInt(ptr + 12, color);
            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);
            MemoryUtil.memPutInt(ptr + 24, overlay);
            MemoryUtil.memPutInt(ptr + 28, light);
            MemoryUtil.memPutInt(ptr + 32, I32_SNorm.packNormal(normalX, normalY, normalZ));
        } else {
            VertexConsumer.super.addVertex(x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
        }
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        long m = this.beginElement(COLOR_SEMANTIC_ID);
        if (m != -1L) {
            MemoryUtil.memPutInt(m, packRgba(r, g, b, a));
        }
        return this;
    }

    private static int packRgba(int r, int g, int b, int a) {
        return (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
    }
}

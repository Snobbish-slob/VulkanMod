package net.vulkanmod.render.vertex;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;

public class CustomVertexFormat {
    private static float POSITION_OFFSET = 4.0f;

    public static final VertexFormat COMPRESSED_TERRAIN =
            VertexFormat.builder(0)
                        .addAttribute("Position", GpuFormat.RGBA16_SINT)
                        .addAttribute("UV0", GpuFormat.RG16_UINT)
                        .addAttribute("Color", GpuFormat.R32_UINT)
                        .build();

    public static final VertexFormat TERRAIN =
            VertexFormat.builder(0)
                        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
                        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
                        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
                        .addAttribute("UV2", GpuFormat.RG16_SINT)
                        .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
                        .build();

    public static final VertexFormat NONE = VertexFormat.builder(0).build();

    public static void setPositionOffset(float positionOffset) {
        POSITION_OFFSET = positionOffset;
    }

    public static float getPositionOffset() {
        return POSITION_OFFSET;
    }
}

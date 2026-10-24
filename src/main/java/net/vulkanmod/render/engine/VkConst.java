package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.lwjgl.vulkan.VK10;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Translations between the game's GPU abstraction enums and Vulkan constants.
 */
public class VkConst {

    public static int of(AddressMode addressMode) {
        return switch (addressMode) {
            case REPEAT -> VK_SAMPLER_ADDRESS_MODE_REPEAT;
            case CLAMP_TO_EDGE -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        };
    }

    public static int of(FilterMode filterMode) {
        return switch (filterMode) {
            case NEAREST -> VK_FILTER_NEAREST;
            case LINEAR -> VK_FILTER_LINEAR;
        };
    }

    public static int mipmapMode(FilterMode filterMode) {
        return switch (filterMode) {
            case NEAREST -> VK_SAMPLER_MIPMAP_MODE_NEAREST;
            case LINEAR -> VK_SAMPLER_MIPMAP_MODE_LINEAR;
        };
    }

    public static int of(CompareOp compareOp) {
        return switch (compareOp) {
            case ALWAYS_PASS -> VK_COMPARE_OP_ALWAYS;
            case LESS_THAN -> VK_COMPARE_OP_LESS;
            case LESS_THAN_OR_EQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL;
            case EQUAL -> VK_COMPARE_OP_EQUAL;
            case NOT_EQUAL -> VK_COMPARE_OP_NOT_EQUAL;
            case GREATER_THAN_OR_EQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
            case GREATER_THAN -> VK_COMPARE_OP_GREATER;
            case NEVER_PASS -> VK_COMPARE_OP_NEVER;
        };
    }

    public static int of(BlendFactor blendFactor) {
        return switch (blendFactor) {
            case CONSTANT_ALPHA -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case CONSTANT_COLOR -> VK_BLEND_FACTOR_CONSTANT_COLOR;
            case DST_ALPHA -> VK_BLEND_FACTOR_DST_ALPHA;
            case DST_COLOR -> VK_BLEND_FACTOR_DST_COLOR;
            case ONE -> VK_BLEND_FACTOR_ONE;
            case ONE_MINUS_CONSTANT_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case ONE_MINUS_DST_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case ONE_MINUS_DST_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case ONE_MINUS_SRC_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case ONE_MINUS_SRC_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case SRC_ALPHA -> VK_BLEND_FACTOR_SRC_ALPHA;
            case SRC_ALPHA_SATURATE -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            case SRC_COLOR -> VK_BLEND_FACTOR_SRC_COLOR;
            case ZERO -> VK_BLEND_FACTOR_ZERO;
        };
    }

    public static int of(BlendOp blendOp) {
        return switch (blendOp) {
            case ADD -> VK_BLEND_OP_ADD;
            case SUBTRACT -> VK_BLEND_OP_SUBTRACT;
            case REVERSE_SUBTRACT -> VK_BLEND_OP_REVERSE_SUBTRACT;
            case MIN -> VK_BLEND_OP_MIN;
            case MAX -> VK_BLEND_OP_MAX;
        };
    }

    public static int of(PolygonMode polygonMode) {
        return switch (polygonMode) {
            case FILL -> VK_POLYGON_MODE_FILL;
            case WIREFRAME -> VK_POLYGON_MODE_LINE;
        };
    }

    public static int of(PrimitiveTopology topology) {
        return switch (topology) {
            case LINES, DEBUG_LINES -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case DEBUG_LINE_STRIP -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case POINTS -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            // Quads are always drawn with a sequential index buffer
            case TRIANGLES, QUADS -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case TRIANGLE_FAN -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
        };
    }

    public static int of(IndexType indexType) {
        return switch (indexType) {
            case SHORT -> VK_INDEX_TYPE_UINT16;
            case INT -> VK_INDEX_TYPE_UINT32;
        };
    }

    public static int of(ShaderType shaderType) {
        return switch (shaderType) {
            case VERTEX -> VK_SHADER_STAGE_VERTEX_BIT;
            case FRAGMENT -> VK_SHADER_STAGE_FRAGMENT_BIT;
        };
    }

    public static int descriptorType(UniformType uniformType) {
        return switch (uniformType) {
            case COMBINED_IMAGE_SAMPLER -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
            case TEXEL_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
        };
    }

    public static int bufferUsage(@GpuBuffer.Usage int usage) {
        // Transfers are always allowed: the backend uses copies to initialize and clear buffers
        int vkUsage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;

        if ((usage & GpuBuffer.USAGE_COPY_SRC) != 0) {
            vkUsage |= VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        }
        if ((usage & GpuBuffer.USAGE_VERTEX) != 0) {
            vkUsage |= VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_INDEX) != 0) {
            vkUsage |= VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_UNIFORM) != 0) {
            vkUsage |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
            vkUsage |= VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_INDIRECT_PARAMETERS) != 0) {
            vkUsage |= VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        }

        return vkUsage;
    }

    public static boolean isMappable(@GpuBuffer.Usage int usage) {
        return (usage & (GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_HINT_CLIENT_STORAGE)) != 0;
    }

    public static int imageUsage(@GpuTexture.Usage int usage, GpuFormat format) {
        // Transfers are always allowed: clears and readbacks use them
        int vkUsage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
            vkUsage |= VK_IMAGE_USAGE_SAMPLED_BIT;
        }
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            vkUsage |= format.hasColorAspect() ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
        }

        return vkUsage;
    }

    public static int imageAspect(GpuFormat format) {
        int aspect = 0;
        if (format.hasColorAspect()) {
            aspect |= VK_IMAGE_ASPECT_COLOR_BIT;
        }
        if (format.hasDepthAspect()) {
            aspect |= VK_IMAGE_ASPECT_DEPTH_BIT;
        }
        if (format.hasStencilAspect()) {
            aspect |= VK_IMAGE_ASPECT_STENCIL_BIT;
        }
        return aspect;
    }

    public static int vkFormat(GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> VK_FORMAT_R8_UNORM;
            case R8_SNORM -> VK_FORMAT_R8_SNORM;
            case RG8_UNORM -> VK_FORMAT_R8G8_UNORM;
            case RG8_SNORM -> VK_FORMAT_R8G8_SNORM;
            case RGB8_UNORM -> VK_FORMAT_R8G8B8_UNORM;
            case RGB8_SNORM -> VK_FORMAT_R8G8B8_SNORM;
            case RGBA8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM;
            case RGBA8_SNORM -> VK_FORMAT_R8G8B8A8_SNORM;
            case R16_UNORM -> VK_FORMAT_R16_UNORM;
            case R16_SNORM -> VK_FORMAT_R16_SNORM;
            case RG16_UNORM -> VK_FORMAT_R16G16_UNORM;
            case RG16_SNORM -> VK_FORMAT_R16G16_SNORM;
            case RGB16_UNORM -> VK_FORMAT_R16G16B16_UNORM;
            case RGB16_SNORM -> VK_FORMAT_R16G16B16_SNORM;
            case RGBA16_UNORM -> VK_FORMAT_R16G16B16A16_UNORM;
            case RGBA16_SNORM -> VK_FORMAT_R16G16B16A16_SNORM;
            case R8_UINT -> VK_FORMAT_R8_UINT;
            case R8_SINT -> VK_FORMAT_R8_SINT;
            case RG8_UINT -> VK_FORMAT_R8G8_UINT;
            case RG8_SINT -> VK_FORMAT_R8G8_SINT;
            case RGB8_UINT -> VK_FORMAT_R8G8B8_UINT;
            case RGB8_SINT -> VK_FORMAT_R8G8B8_SINT;
            case RGBA8_UINT -> VK_FORMAT_R8G8B8A8_UINT;
            case RGBA8_SINT -> VK_FORMAT_R8G8B8A8_SINT;
            case R16_UINT -> VK_FORMAT_R16_UINT;
            case R16_SINT -> VK_FORMAT_R16_SINT;
            case RG16_UINT -> VK_FORMAT_R16G16_UINT;
            case RG16_SINT -> VK_FORMAT_R16G16_SINT;
            case RGB16_UINT -> VK_FORMAT_R16G16B16_UINT;
            case RGB16_SINT -> VK_FORMAT_R16G16B16_SINT;
            case RGBA16_UINT -> VK_FORMAT_R16G16B16A16_UINT;
            case RGBA16_SINT -> VK_FORMAT_R16G16B16A16_SINT;
            case R32_UINT -> VK_FORMAT_R32_UINT;
            case R32_SINT -> VK_FORMAT_R32_SINT;
            case RG32_UINT -> VK_FORMAT_R32G32_UINT;
            case RG32_SINT -> VK_FORMAT_R32G32_SINT;
            case RGB32_UINT -> VK_FORMAT_R32G32B32_UINT;
            case RGB32_SINT -> VK_FORMAT_R32G32B32_SINT;
            case RGBA32_UINT -> VK_FORMAT_R32G32B32A32_UINT;
            case RGBA32_SINT -> VK_FORMAT_R32G32B32A32_SINT;
            case R16_FLOAT -> VK_FORMAT_R16_SFLOAT;
            case RG16_FLOAT -> VK_FORMAT_R16G16_SFLOAT;
            case RGB16_FLOAT -> VK_FORMAT_R16G16B16_SFLOAT;
            case RGBA16_FLOAT -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case R32_FLOAT -> VK_FORMAT_R32_SFLOAT;
            case RG32_FLOAT -> VK_FORMAT_R32G32_SFLOAT;
            case RGB32_FLOAT -> VK_FORMAT_R32G32B32_SFLOAT;
            case RGBA32_FLOAT -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case RGB10A2_UNORM -> VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            case RGB10A2_UINT -> VK_FORMAT_A2B10G10R10_UINT_PACK32;
            case RG11B10_FLOAT -> VK_FORMAT_B10G11R11_UFLOAT_PACK32;
            case D32_FLOAT -> VK_FORMAT_D32_SFLOAT;
            case D32_FLOAT_S8_UINT -> VK_FORMAT_D32_SFLOAT_S8_UINT;
            case D24_UNORM_S8_UINT -> VK_FORMAT_D24_UNORM_S8_UINT;
            case D16_UNORM -> VK_FORMAT_D16_UNORM;
            case S8_UINT -> VK10.VK_FORMAT_S8_UINT;
        };
    }
}

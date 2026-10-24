package net.vulkanmod.vulkan.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class GraphicsPipeline extends Pipeline {
    private final Object2LongMap<PipelineState> graphicsPipelines = new Object2LongOpenHashMap<>();

    private final VertexFormat vertexFormat;
    private final VertexInputDescription vertexInputDescription;

    private long vertShaderModule = 0;
    private long fragShaderModule = 0;

    GraphicsPipeline(Builder builder) {
        super(builder.name);
        this.buffers = builder.UBOs;
        this.imageDescriptors = builder.imageDescriptors;
        this.pushConstants = builder.pushConstants;
        this.vertexFormat = builder.vertexFormat;

        this.vertexInputDescription = new VertexInputDescription(this.vertexFormat);

        createDescriptorSetLayout();
        createPipelineLayout();

        createShaderModules(builder);

        if (builder.renderPass != null)
            graphicsPipelines.computeIfAbsent(PipelineState.DEFAULT,
                    this::createGraphicsPipeline);

        createDescriptorSets(Renderer.getFramesNum());

        PIPELINES.add(this);
    }

    public long getHandle(PipelineState state) {
        return graphicsPipelines.computeIfAbsent(state, this::createGraphicsPipeline);
    }

    private long createGraphicsPipeline(PipelineState state) {
        try (MemoryStack stack = stackPush()) {
            ByteBuffer entryPoint = stack.UTF8("main");

            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);

            VkPipelineShaderStageCreateInfo vertShaderStageInfo = shaderStages.get(0);

            vertShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            vertShaderStageInfo.stage(VK_SHADER_STAGE_VERTEX_BIT);
            vertShaderStageInfo.module(vertShaderModule);
            vertShaderStageInfo.pName(entryPoint);

            VkPipelineShaderStageCreateInfo fragShaderStageInfo = shaderStages.get(1);

            fragShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            fragShaderStageInfo.stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            fragShaderStageInfo.module(fragShaderModule);
            fragShaderStageInfo.pName(entryPoint);

            // ===> VERTEX STAGE <===

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack);
            vertexInputInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);

            if (vertexInputDescription != null) {
                vertexInputInfo.pVertexBindingDescriptions(vertexInputDescription.bindingDescriptions);
                vertexInputInfo.pVertexAttributeDescriptions(vertexInputDescription.attributeDescriptions);
            }

            // ===> ASSEMBLY STAGE <===

            final int topology = PipelineState.AssemblyRasterState.decodeTopology(state.assemblyRasterState);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack);
            inputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            inputAssembly.topology(topology);
            inputAssembly.primitiveRestartEnable(false);

            // ===> VIEWPORT & SCISSOR

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack);
            viewportState.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);

            viewportState.viewportCount(1);
            viewportState.scissorCount(1);

            // ===> RASTERIZATION STAGE <===

            final int polygonMode = PipelineState.AssemblyRasterState.decodePolygonMode(state.assemblyRasterState);
            final int cullMode = PipelineState.AssemblyRasterState.decodeCullMode(state.assemblyRasterState);

            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack);
            rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            rasterizer.depthClampEnable(false);
            rasterizer.rasterizerDiscardEnable(false);
            rasterizer.polygonMode(polygonMode);
            rasterizer.lineWidth(1.0f);
            rasterizer.cullMode(cullMode);
            rasterizer.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            rasterizer.depthBiasEnable(true);

            // ===> MULTISAMPLING <===

            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack);
            multisampling.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
            multisampling.sampleShadingEnable(false);
            multisampling.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            // ===> DEPTH TEST <===

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack);
            depthStencil.sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO);
            depthStencil.depthTestEnable(PipelineState.DepthState.depthTest(state.depthState_i));
            depthStencil.depthWriteEnable(PipelineState.DepthState.depthMask(state.depthState_i));
            depthStencil.depthCompareOp(PipelineState.DepthState.decodeDepthFun(state.depthState_i));
            depthStencil.depthBoundsTestEnable(false);
            depthStencil.minDepthBounds(0.0f); // Optional
            depthStencil.maxDepthBounds(1.0f); // Optional
            depthStencil.stencilTestEnable(false);

            // ===> COLOR BLENDING <===

            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            colorBlendAttachment.colorWriteMask(state.colorMask_i);

            if (PipelineState.BlendState.enable(state.blendState_i)) {
                colorBlendAttachment.blendEnable(true);
                colorBlendAttachment.srcColorBlendFactor(PipelineState.BlendState.getSrcRgbFactor(state.blendState_i));
                colorBlendAttachment.dstColorBlendFactor(PipelineState.BlendState.getDstRgbFactor(state.blendState_i));
                colorBlendAttachment.colorBlendOp(PipelineState.BlendState.blendOp(state.blendState_i));
                colorBlendAttachment.srcAlphaBlendFactor(PipelineState.BlendState.getSrcAlphaFactor(state.blendState_i));
                colorBlendAttachment.dstAlphaBlendFactor(PipelineState.BlendState.getDstAlphaFactor(state.blendState_i));
                colorBlendAttachment.alphaBlendOp(PipelineState.BlendState.blendOp(state.blendState_i));
            }
            else {
                colorBlendAttachment.blendEnable(false);
            }

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack);
            colorBlending.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            colorBlending.logicOpEnable(PipelineState.LogicOpState.enable(state.logicOp_i));
            colorBlending.logicOp(PipelineState.LogicOpState.decodeFun(state.logicOp_i));
            colorBlending.pAttachments(colorBlendAttachment);
            colorBlending.blendConstants(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));

            // ===> DYNAMIC STATES <===

            VkPipelineDynamicStateCreateInfo dynamicStates = VkPipelineDynamicStateCreateInfo.calloc(stack);
            dynamicStates.sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO);

            if (topology == VK_PRIMITIVE_TOPOLOGY_LINE_LIST || polygonMode == VK_POLYGON_MODE_LINE) {
                dynamicStates.pDynamicStates(
                        stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR,
                                   VK_DYNAMIC_STATE_LINE_WIDTH));
            }
            else {
                dynamicStates.pDynamicStates(
                        stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            }

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO);
            pipelineInfo.pStages(shaderStages);
            pipelineInfo.pVertexInputState(vertexInputInfo);
            pipelineInfo.pInputAssemblyState(inputAssembly);
            pipelineInfo.pViewportState(viewportState);
            pipelineInfo.pRasterizationState(rasterizer);
            pipelineInfo.pMultisampleState(multisampling);
            pipelineInfo.pDepthStencilState(depthStencil);
            pipelineInfo.pColorBlendState(colorBlending);
            pipelineInfo.pDynamicState(dynamicStates);
            pipelineInfo.layout(pipelineLayout);
            pipelineInfo.basePipelineHandle(VK_NULL_HANDLE);
            pipelineInfo.basePipelineIndex(-1);

            if (!Vulkan.DYNAMIC_RENDERING) {
                pipelineInfo.renderPass(state.renderPass.getId());
                pipelineInfo.subpass(0);
            }
            else {
                //dyn-rendering
                VkPipelineRenderingCreateInfoKHR renderingInfo = VkPipelineRenderingCreateInfoKHR.calloc(stack);
                renderingInfo.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR);

                Framebuffer framebuffer;
                if (state.renderPass != null) {
                    framebuffer = state.renderPass.getFramebuffer();
                } else {
                    framebuffer = Renderer.getInstance().getMainPass().getMainFramebuffer();
                }

                renderingInfo.pColorAttachmentFormats(stack.ints(framebuffer.getFormat()));
                renderingInfo.depthAttachmentFormat(framebuffer.getDepthFormat());
                pipelineInfo.pNext(renderingInfo);
            }

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);

            Vulkan.checkResult(vkCreateGraphicsPipelines(DeviceManager.vkDevice, PIPELINE_CACHE, pipelineInfo, null, pGraphicsPipeline),
                               "Failed to create graphics pipeline " + this.name);

            return pGraphicsPipeline.get(0);
        }
    }

    private void createShaderModules(Builder builder) {
        ByteBuffer vsSpirv;
        if (builder.shadersSpirv.containsKey(SpirvCompiler.ShaderKind.VERTEX_SHADER)) {
            vsSpirv = builder.shadersSpirv.get(SpirvCompiler.ShaderKind.VERTEX_SHADER);
        }
        else {
            String vsh = builder.shadersSrc.get(SpirvCompiler.ShaderKind.VERTEX_SHADER);
            SpirvCompiler.SPIRV vertShaderSPIRV = SpirvCompiler.compileShader(String.format("%s.vsh", name), vsh, SpirvCompiler.ShaderKind.VERTEX_SHADER);
            vsSpirv = vertShaderSPIRV.bytecode();
        }

        ByteBuffer fsSpirv;
        if (builder.shadersSpirv.containsKey(SpirvCompiler.ShaderKind.FRAGMENT_SHADER)) {
            fsSpirv = builder.shadersSpirv.get(SpirvCompiler.ShaderKind.FRAGMENT_SHADER);
        }
        else  {
            String fsh = builder.shadersSrc.get(SpirvCompiler.ShaderKind.FRAGMENT_SHADER);
            SpirvCompiler.SPIRV fragShaderSPIRV = SpirvCompiler.compileShader(String.format("%s.fsh", name), fsh, SpirvCompiler.ShaderKind.FRAGMENT_SHADER);
            fsSpirv = fragShaderSPIRV.bytecode();
        }


        this.vertShaderModule = createShaderModule(vsSpirv);
        this.fragShaderModule = createShaderModule(fsSpirv);
    }

    public void cleanUp() {
        vkDestroyShaderModule(DeviceManager.vkDevice, vertShaderModule, null);
        vkDestroyShaderModule(DeviceManager.vkDevice, fragShaderModule, null);

        vertexInputDescription.cleanUp();

        destroyDescriptorSets();

        graphicsPipelines.forEach((state, pipeline) -> {
            vkDestroyPipeline(DeviceManager.vkDevice, pipeline, null);
        });
        graphicsPipelines.clear();

        vkDestroyDescriptorSetLayout(DeviceManager.vkDevice, descriptorSetLayout, null);
        vkDestroyPipelineLayout(DeviceManager.vkDevice, pipelineLayout, null);

        PIPELINES.remove(this);
        Renderer.getInstance().removeUsedPipeline(this);
    }

    static class VertexInputDescription {
        final VkVertexInputAttributeDescription.Buffer attributeDescriptions;
        final VkVertexInputBindingDescription.Buffer bindingDescriptions;

        VertexInputDescription(VertexFormat vertexFormat) {
            if (!vertexFormat.getElements().isEmpty()) {
                this.bindingDescriptions = getBindingDescription(vertexFormat);
                this.attributeDescriptions = getAttributeDescriptions(vertexFormat);
            }
            else {
                this.bindingDescriptions = null;
                this.attributeDescriptions = null;
            }
        }

        void cleanUp() {
            if (this.bindingDescriptions != null) {
                MemoryUtil.memFree(this.bindingDescriptions);
                MemoryUtil.memFree(this.attributeDescriptions);
            }
        }
    }

    private static VkVertexInputBindingDescription.Buffer getBindingDescription(VertexFormat vertexFormat) {
        VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1);

        bindingDescription.binding(0);
        bindingDescription.stride(vertexFormat.getVertexSize());
        bindingDescription.inputRate(vertexFormat.getStepRate() > 0 ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX);

        return bindingDescription;
    }

    private static VkVertexInputAttributeDescription.Buffer getAttributeDescriptions(VertexFormat vertexFormat) {
        List<VertexFormatElement> elements = vertexFormat.getElements();

        int size = elements.size();

        VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(size);

        for (int i = 0; i < size; ++i) {
            VkVertexInputAttributeDescription posDescription = attributeDescriptions.get(i);
            posDescription.binding(0);
            posDescription.location(i);

            VertexFormatElement formatElement = elements.get(i);
            posDescription.format(vkVertexFormat(formatElement.format()));
            posDescription.offset(formatElement.offset());
        }

        return attributeDescriptions;
    }

    public static int vkVertexFormat(GpuFormat format) {
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
            default -> throw new RuntimeException(String.format("Unsupported vertex attribute format: %s", format));
        };
    }
}

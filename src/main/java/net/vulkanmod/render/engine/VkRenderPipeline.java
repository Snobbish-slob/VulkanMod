package net.vulkanmod.render.engine;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Backend pipeline built from the SPIR-V modules and fixed state the game provides.
 * <p>
 * Descriptor bindings are already assigned by the game (binding index = uniform index in
 * {@link CreateInfo#uniforms()}), so the layout is created directly from that list. Pipeline
 * objects are created lazily per attachment format combination.
 */
public class VkRenderPipeline implements BackendRenderPipeline {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int POOL_SETS = 128;

    private static final Set<VkRenderPipeline> ACTIVE_PIPELINES = new ObjectOpenHashSet<>();
    private static boolean frameListenerRegistered = false;

    private static synchronized void registerPipeline(VkRenderPipeline pipeline) {
        if (!frameListenerRegistered) {
            Renderer.getInstance().addFrameListener(new Renderer.FrameListener() {
                @Override
                public void onBeginFrame(int frame) {
                    synchronized (VkRenderPipeline.class) {
                        for (VkRenderPipeline p : ACTIVE_PIPELINES) {
                            p.resetFramePools(frame);
                        }
                    }
                }

                @Override
                public void onFramesNumChanged(int framesNum) {
                    synchronized (VkRenderPipeline.class) {
                        for (VkRenderPipeline p : ACTIVE_PIPELINES) {
                            p.recreateFramePools(framesNum);
                        }
                    }
                }
            });
            frameListenerRegistered = true;
        }

        ACTIVE_PIPELINES.add(pipeline);
    }

    private static synchronized void unregisterPipeline(VkRenderPipeline pipeline) {
        ACTIVE_PIPELINES.remove(pipeline);
    }

    public final String name;
    private final CreateInfo createInfo;

    private final long[] shaderModules;
    private final int[] shaderStages;

    private final int uniformCount;
    private final int[] descriptorTypes;
    private final int[] uniformDynamicOffsetIndex;
    private final int dynamicOffsetCount;
    private final int[] texelFormats;

    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final int pushConstantStages;

    private final Long2LongOpenHashMap pipelines = new Long2LongOpenHashMap();
    private FramePools[] framePools;

    private boolean closed;

    public VkRenderPipeline(CreateInfo createInfo) {
        this.name = createInfo.name();
        this.createInfo = createInfo;

        List<CreateInfo.Shader> shaders = createInfo.shaders();
        this.shaderModules = new long[shaders.size()];
        this.shaderStages = new int[shaders.size()];

        List<BindGroupLayout.UniformDescription> uniforms = createInfo.uniforms();
        this.uniformCount = uniforms.size();
        this.descriptorTypes = new int[this.uniformCount];
        this.uniformDynamicOffsetIndex = new int[this.uniformCount];
        this.texelFormats = new int[this.uniformCount];
        int[] bindingStages = new int[this.uniformCount];

        int dynamicOffsets = 0;
        for (int i = 0; i < this.uniformCount; i++) {
            BindGroupLayout.UniformDescription uniform = uniforms.get(i);
            this.descriptorTypes[i] = VkConst.descriptorType(uniform.type());
            this.uniformDynamicOffsetIndex[i] = uniform.type() == UniformType.UNIFORM_BUFFER ? dynamicOffsets++ : -1;
            this.texelFormats[i] = uniform.gpuFormat() != null ? VkConst.vkFormat(uniform.gpuFormat()) : 0;
        }
        this.dynamicOffsetCount = dynamicOffsets;

        int pushConstantStages = 0;

        for (int i = 0; i < shaders.size(); i++) {
            CreateInfo.Shader shader = shaders.get(i);
            SpvModule module = shader.module();
            int stage = VkConst.of(module.type());

            this.shaderStages[i] = stage;
            this.shaderModules[i] = Pipeline.createShaderModule(module.spv());

            SpvModule.Reflection reflection = module.getReflectionInfoIfAvailable();
            if (reflection == null) {
                try {
                    reflection = module.reflect();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to reflect shader %s".formatted(shader.name()), e);
                }
            }

            for (SpvModule.Reflection.Descriptor descriptor : reflection.descriptors()) {
                int binding = descriptor.binding();
                if (binding >= 0 && binding < this.uniformCount) {
                    bindingStages[binding] |= stage;
                }
            }

            if (!reflection.pushConstants().isEmpty()) {
                pushConstantStages |= stage;
            }
        }

        if (createInfo.pushConstantsSize() > 0 && pushConstantStages == 0) {
            pushConstantStages = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        }
        this.pushConstantStages = pushConstantStages;

        for (int i = 0; i < this.uniformCount; i++) {
            if (bindingStages[i] == 0) {
                bindingStages[i] = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
            }
        }

        this.descriptorSetLayout = createDescriptorSetLayout(bindingStages);
        this.pipelineLayout = createPipelineLayout();

        this.framePools = createFramePools(Renderer.getFramesNum());

        registerPipeline(this);
    }

    private long createDescriptorSetLayout(int[] bindingStages) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(this.uniformCount, stack);

            for (int i = 0; i < this.uniformCount; i++) {
                VkDescriptorSetLayoutBinding binding = bindings.get(i);
                binding.binding(i);
                binding.descriptorCount(1);
                binding.descriptorType(this.descriptorTypes[i]);
                binding.pImmutableSamplers(null);
                binding.stageFlags(bindingStages[i]);
            }

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
            layoutInfo.sType$Default();
            layoutInfo.pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            Vulkan.checkResult(vkCreateDescriptorSetLayout(Vulkan.getVkDevice(), layoutInfo, null, pLayout),
                               "Failed to create descriptor set layout for pipeline " + this.name);

            return pLayout.get(0);
        }
    }

    private long createPipelineLayout() {
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
            layoutInfo.sType$Default();
            layoutInfo.pSetLayouts(stack.longs(this.descriptorSetLayout));

            if (this.createInfo.pushConstantsSize() > 0) {
                VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
                range.stageFlags(this.pushConstantStages);
                range.offset(0);
                range.size(this.createInfo.pushConstantsSize());

                layoutInfo.pPushConstantRanges(range);
            }

            LongBuffer pLayout = stack.mallocLong(1);
            Vulkan.checkResult(vkCreatePipelineLayout(Vulkan.getVkDevice(), layoutInfo, null, pLayout),
                               "Failed to create pipeline layout for pipeline " + this.name);

            return pLayout.get(0);
        }
    }

    /**
     * Returns the pipeline object compatible with the given attachment formats (0 = no attachment).
     */
    public long getHandle(int colorFormat, int depthFormat) {
        long key = ((long) colorFormat << 32) | (depthFormat & 0xFFFFFFFFL);

        long handle = this.pipelines.get(key);
        if (handle == 0L) {
            handle = createPipeline(colorFormat, depthFormat);
            this.pipelines.put(key, handle);
        }

        return handle;
    }

    private long createPipeline(int colorFormat, int depthFormat) {
        try (MemoryStack stack = stackPush()) {
            ByteBuffer entryPoint = stack.UTF8("main");

            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(this.shaderModules.length, stack);
            for (int i = 0; i < this.shaderModules.length; i++) {
                VkPipelineShaderStageCreateInfo stageInfo = shaderStages.get(i);
                stageInfo.sType$Default();
                stageInfo.stage(this.shaderStages[i]);
                stageInfo.module(this.shaderModules[i]);
                stageInfo.pName(entryPoint);
            }

            // ===> VERTEX INPUT <===
            List<CreateInfo.VertexBuffer> vertexBuffers = this.createInfo.vertexBuffers();
            List<CreateInfo.AttribBinding> attribBindings = this.createInfo.attribBindings();

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack);
            vertexInputInfo.sType$Default();

            if (!vertexBuffers.isEmpty()) {
                VkVertexInputBindingDescription.Buffer bindingDescriptions = VkVertexInputBindingDescription.calloc(vertexBuffers.size(), stack);
                for (int i = 0; i < vertexBuffers.size(); i++) {
                    CreateInfo.VertexBuffer vertexBuffer = vertexBuffers.get(i);
                    bindingDescriptions.get(i)
                                       .binding(vertexBuffer.bufferSlot())
                                       .stride(vertexBuffer.stride())
                                       .inputRate(vertexBuffer.stepRate() > 0 ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX);
                }

                VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(attribBindings.size(), stack);
                for (int i = 0; i < attribBindings.size(); i++) {
                    CreateInfo.AttribBinding attrib = attribBindings.get(i);
                    attributeDescriptions.get(i)
                                         .location(attrib.location())
                                         .binding(attrib.bufferSlot())
                                         .format(VkConst.vkFormat(attrib.format()))
                                         .offset(attrib.offset());
                }

                vertexInputInfo.pVertexBindingDescriptions(bindingDescriptions);
                vertexInputInfo.pVertexAttributeDescriptions(attributeDescriptions);
            }

            // ===> ASSEMBLY STAGE <===
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack);
            inputAssembly.sType$Default();
            inputAssembly.topology(VkConst.of(this.createInfo.primitiveTopology()));
            inputAssembly.primitiveRestartEnable(false);

            // ===> VIEWPORT & SCISSOR (dynamic) <===
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack);
            viewportState.sType$Default();
            viewportState.viewportCount(1);
            viewportState.scissorCount(1);

            // ===> RASTERIZATION STAGE <===
            DepthStencilState depthStencilState = this.createInfo.depthStencilState();

            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack);
            rasterizer.sType$Default();
            rasterizer.depthClampEnable(false);
            rasterizer.rasterizerDiscardEnable(false);
            rasterizer.polygonMode(VkConst.of(this.createInfo.polygonMode()));
            rasterizer.lineWidth(1.0f);
            rasterizer.cullMode(this.createInfo.cull() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE);
            // The game renders with a non-flipped viewport, which mirrors the winding compared to OpenGL
            rasterizer.frontFace(VK_FRONT_FACE_CLOCKWISE);

            if (depthStencilState != null) {
                boolean depthBias = depthStencilState.depthBiasConstant() != 0.0f || depthStencilState.depthBiasScaleFactor() != 0.0f;
                rasterizer.depthBiasEnable(depthBias);
                rasterizer.depthBiasConstantFactor(depthStencilState.depthBiasConstant());
                rasterizer.depthBiasSlopeFactor(depthStencilState.depthBiasScaleFactor());
            }

            // ===> MULTISAMPLING <===
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack);
            multisampling.sType$Default();
            multisampling.sampleShadingEnable(false);
            multisampling.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            // ===> DEPTH TEST <===
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack);
            depthStencil.sType$Default();

            boolean hasDepth = depthFormat != VK_FORMAT_UNDEFINED && depthStencilState != null;
            depthStencil.depthTestEnable(hasDepth);
            depthStencil.depthWriteEnable(hasDepth && depthStencilState.writeDepth());
            depthStencil.depthCompareOp(hasDepth ? VkConst.of(depthStencilState.depthTest()) : VK_COMPARE_OP_ALWAYS);
            depthStencil.depthBoundsTestEnable(false);
            depthStencil.minDepthBounds(0.0f);
            depthStencil.maxDepthBounds(1.0f);
            depthStencil.stencilTestEnable(false);

            // ===> COLOR BLENDING <===
            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack);
            colorBlending.sType$Default();
            colorBlending.logicOpEnable(false);
            colorBlending.blendConstants(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));

            List<ColorTargetState> colorTargetStates = this.createInfo.colorTargetStates();
            ColorTargetState colorTargetState = colorTargetStates.isEmpty() ? null : colorTargetStates.get(0);

            if (colorFormat != VK_FORMAT_UNDEFINED) {
                VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);

                int writeMask = colorTargetState != null ? colorTargetState.writeMask() : ColorTargetState.WRITE_ALL;
                colorBlendAttachment.colorWriteMask(writeMask);

                Optional<com.mojang.renderpearl.api.pipeline.BlendFunction> blendFunction =
                        colorTargetState != null ? colorTargetState.blendFunction() : Optional.empty();

                if (blendFunction.isPresent()) {
                    var blend = blendFunction.get();
                    colorBlendAttachment.blendEnable(true);
                    colorBlendAttachment.srcColorBlendFactor(VkConst.of(blend.color().sourceFactor()));
                    colorBlendAttachment.dstColorBlendFactor(VkConst.of(blend.color().destFactor()));
                    colorBlendAttachment.colorBlendOp(VkConst.of(blend.color().op()));
                    colorBlendAttachment.srcAlphaBlendFactor(VkConst.of(blend.alpha().sourceFactor()));
                    colorBlendAttachment.dstAlphaBlendFactor(VkConst.of(blend.alpha().destFactor()));
                    colorBlendAttachment.alphaBlendOp(VkConst.of(blend.alpha().op()));
                }
                else {
                    colorBlendAttachment.blendEnable(false);
                }

                colorBlending.pAttachments(colorBlendAttachment);
            }

            // ===> DYNAMIC STATES <===
            VkPipelineDynamicStateCreateInfo dynamicStates = VkPipelineDynamicStateCreateInfo.calloc(stack);
            dynamicStates.sType$Default();
            dynamicStates.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            // ===> DYNAMIC RENDERING <===
            VkPipelineRenderingCreateInfoKHR renderingInfo = VkPipelineRenderingCreateInfoKHR.calloc(stack);
            renderingInfo.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR);
            if (colorFormat != VK_FORMAT_UNDEFINED) {
                renderingInfo.colorAttachmentCount(1);
                renderingInfo.pColorAttachmentFormats(stack.ints(colorFormat));
            }
            renderingInfo.depthAttachmentFormat(depthFormat);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.sType$Default();
            pipelineInfo.pNext(renderingInfo);
            pipelineInfo.pStages(shaderStages);
            pipelineInfo.pVertexInputState(vertexInputInfo);
            pipelineInfo.pInputAssemblyState(inputAssembly);
            pipelineInfo.pViewportState(viewportState);
            pipelineInfo.pRasterizationState(rasterizer);
            pipelineInfo.pMultisampleState(multisampling);
            pipelineInfo.pDepthStencilState(depthStencil);
            pipelineInfo.pColorBlendState(colorBlending);
            pipelineInfo.pDynamicState(dynamicStates);
            pipelineInfo.layout(this.pipelineLayout);
            pipelineInfo.basePipelineHandle(VK_NULL_HANDLE);
            pipelineInfo.basePipelineIndex(-1);

            LongBuffer pPipeline = stack.mallocLong(1);
            Vulkan.checkResult(vkCreateGraphicsPipelines(Vulkan.getVkDevice(), Pipeline.getPipelineCache(), pipelineInfo, null, pPipeline),
                               "Failed to create graphics pipeline " + this.name);

            return pPipeline.get(0);
        }
    }

    // Descriptor pools

    private FramePools[] createFramePools(int frames) {
        FramePools[] pools = new FramePools[frames];
        for (int i = 0; i < frames; i++) {
            pools[i] = new FramePools();
        }
        return pools;
    }

    private void resetFramePools(int frame) {
        if (frame < this.framePools.length) {
            this.framePools[frame].reset();
        }
    }

    private void recreateFramePools(int frames) {
        for (FramePools pools : this.framePools) {
            pools.destroy();
        }
        this.framePools = createFramePools(frames);
    }

    /**
     * Allocates a fresh descriptor set for the current frame.
     */
    public long allocateDescriptorSet(int frame) {
        return this.framePools[frame].allocate();
    }

    public long getLayout() {
        return this.pipelineLayout;
    }

    public int getPushConstantStages() {
        return this.pushConstantStages;
    }

    public int getUniformCount() {
        return this.uniformCount;
    }

    public int getDescriptorType(int binding) {
        return this.descriptorTypes[binding];
    }

    public int getDynamicOffsetIndex(int binding) {
        return this.uniformDynamicOffsetIndex[binding];
    }

    public int getDynamicOffsetCount() {
        return this.dynamicOffsetCount;
    }

    public int getTexelFormat(int binding) {
        return this.texelFormats[binding];
    }

    public CreateInfo getCreateInfo() {
        return this.createInfo;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;

        unregisterPipeline(this);

        final long[] pipelineHandles = this.pipelines.values().toLongArray();
        final long[] modules = this.shaderModules.clone();
        final long layout = this.pipelineLayout;
        final long setLayout = this.descriptorSetLayout;
        final FramePools[] pools = this.framePools;

        MemoryManager.getInstance().addFrameOp(() -> {
            VkDevice device = Vulkan.getVkDevice();

            for (long handle : pipelineHandles) {
                vkDestroyPipeline(device, handle, null);
            }
            for (long module : modules) {
                vkDestroyShaderModule(device, module, null);
            }
            for (FramePools framePool : pools) {
                framePool.destroy();
            }

            vkDestroyPipelineLayout(device, layout, null);
            vkDestroyDescriptorSetLayout(device, setLayout, null);
        });
    }

    /**
     * Descriptor pools of one frame slot. Sets are allocated linearly and all pools are reset
     * when the frame slot is reused.
     */
    private class FramePools {
        private final LongArrayList pools = new LongArrayList();
        private int poolIndex = -1;
        private int setsLeft = 0;

        long allocate() {
            if (this.setsLeft == 0) {
                this.poolIndex++;
                if (this.poolIndex >= this.pools.size()) {
                    this.pools.add(createPool());
                }
                this.setsLeft = POOL_SETS;
            }

            try (MemoryStack stack = stackPush()) {
                VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
                allocInfo.sType$Default();
                allocInfo.descriptorPool(this.pools.getLong(this.poolIndex));
                allocInfo.pSetLayouts(stack.longs(VkRenderPipeline.this.descriptorSetLayout));

                LongBuffer pSet = stack.mallocLong(1);
                int result = vkAllocateDescriptorSets(Vulkan.getVkDevice(), allocInfo, pSet);
                if (result != VK_SUCCESS) {
                    throw new RuntimeException("Failed to allocate descriptor set for pipeline %s: %d".formatted(VkRenderPipeline.this.name, result));
                }

                this.setsLeft--;
                return pSet.get(0);
            }
        }

        private long createPool() {
            try (MemoryStack stack = stackPush()) {
                int bindingCount = Math.max(1, VkRenderPipeline.this.uniformCount);
                VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(bindingCount, stack);

                if (VkRenderPipeline.this.uniformCount == 0) {
                    poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
                }

                for (int i = 0; i < VkRenderPipeline.this.uniformCount; i++) {
                    poolSizes.get(i).type(VkRenderPipeline.this.descriptorTypes[i]).descriptorCount(POOL_SETS);
                }

                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
                poolInfo.sType$Default();
                poolInfo.pPoolSizes(poolSizes);
                poolInfo.maxSets(POOL_SETS);

                LongBuffer pPool = stack.mallocLong(1);
                Vulkan.checkResult(vkCreateDescriptorPool(Vulkan.getVkDevice(), poolInfo, null, pPool),
                                   "Failed to create descriptor pool for pipeline " + VkRenderPipeline.this.name);

                return pPool.get(0);
            }
        }

        void reset() {
            for (int i = 0; i <= Math.min(this.poolIndex, this.pools.size() - 1); i++) {
                vkResetDescriptorPool(Vulkan.getVkDevice(), this.pools.getLong(i), 0);
            }
            this.poolIndex = -1;
            this.setsLeft = 0;
        }

        void destroy() {
            for (int i = 0; i < this.pools.size(); i++) {
                vkDestroyDescriptorPool(Vulkan.getVkDevice(), this.pools.getLong(i), null);
            }
            this.pools.clear();
            this.poolIndex = -1;
            this.setsLeft = 0;
        }
    }
}

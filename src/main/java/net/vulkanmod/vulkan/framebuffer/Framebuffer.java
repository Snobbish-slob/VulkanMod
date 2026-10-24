package net.vulkanmod.vulkan.framebuffer;

import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.commons.lang3.Validate;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Arrays;

import static net.vulkanmod.vulkan.Vulkan.DYNAMIC_RENDERING;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A set of attachments (at most one color and one depth image) that can be rendered to.
 * <p>
 * The attachment image views and the render size can be overridden, which is used by the
 * backend when rendering to a single mip level of a texture.
 */
public class Framebuffer {
    public static final int DEFAULT_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

    public final String name;
    protected int format;
    protected int depthFormat;
    protected int width, height;
    protected boolean linearFiltering;
    protected boolean depthLinearFiltering;
    protected int attachmentCount;

    boolean hasColorAttachment;
    boolean hasDepthAttachment;

    private VulkanImage colorAttachment;
    protected VulkanImage depthAttachment;

    private long colorViewOverride;
    private long depthViewOverride;

    private int level;

    private final Reference2LongArrayMap<RenderPass> renderpassToFramebufferMap = new Reference2LongArrayMap<>();

    protected Framebuffer() {
        this.name = null;
    }

    public Framebuffer(Builder builder) {
        this.name = builder.name;
        this.format = builder.format;
        this.depthFormat = builder.depthFormat;
        this.width = builder.width;
        this.height = builder.height;
        this.linearFiltering = builder.linearFiltering;
        this.depthLinearFiltering = builder.depthLinearFiltering;
        this.hasColorAttachment = builder.hasColorAttachment;
        this.hasDepthAttachment = builder.hasDepthAttachment;

        if (builder.createImages)
            this.createImages();
        else {
            this.colorAttachment = builder.colorAttachment;
            this.depthAttachment = builder.depthAttachment;
        }

        this.colorViewOverride = builder.colorView;
        this.depthViewOverride = builder.depthView;
        this.level = builder.level;
    }

    public void createImages() {
        if (this.hasColorAttachment) {
            this.colorAttachment = VulkanImage.builder(this.width, this.height)
                                              .setName(this.name != null ? String.format("%s Color", this.name) : null)
                                              .setFormat(format)
                                              .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                                              .setLinearFiltering(linearFiltering)
                                              .setClamp(true)
                                              .createVulkanImage();
        }

        if (this.hasDepthAttachment) {
            this.depthAttachment = VulkanImage.builder(width, height)
                                              .setName(this.name != null ? String.format("%s Depth", this.name) : null)
                                              .setFormat(depthFormat)
                                              .setUsage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                                              .setLinearFiltering(depthLinearFiltering)
                                              .setClamp(true)
                                              .createVulkanImage();

            this.attachmentCount++;
        }
    }

    public void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;

        this.cleanUp();

        this.createImages();
    }

    private long createFramebuffer(RenderPass renderPass) {
        try (MemoryStack stack = MemoryStack.stackPush()) {

            LongBuffer attachments;
            if (colorAttachment != null && depthAttachment != null) {
                attachments = stack.longs(getColorAttachmentView(), getDepthImageView());
            } else if (colorAttachment != null) {
                attachments = stack.longs(getColorAttachmentView());
            } else if (depthAttachment != null) {
                attachments = stack.longs(getDepthImageView());
            } else {
                throw new IllegalStateException();
            }

            LongBuffer pFramebuffer = stack.mallocLong(1);

            VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack);
            framebufferInfo.sType$Default();
            framebufferInfo.renderPass(renderPass.getId());
            framebufferInfo.width(this.width);
            framebufferInfo.height(this.height);
            framebufferInfo.layers(1);
            framebufferInfo.pAttachments(attachments);

            if (VK10.vkCreateFramebuffer(Vulkan.getVkDevice(), framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create framebuffer");
            }

            return pFramebuffer.get(0);
        }
    }

    public void beginRenderPass(VkCommandBuffer commandBuffer, RenderPass renderPass, MemoryStack stack,
                                RenderPass.BeginInfo beginInfo) {
        renderPass.setFramebuffer(this);

        if (!DYNAMIC_RENDERING) {
            long framebufferId = this.getFramebufferId(renderPass);
            renderPass.beginRenderPass(commandBuffer, framebufferId, stack, beginInfo);
        }
        else {
            renderPass.beginDynamicRendering(commandBuffer, stack, beginInfo);
        }
    }

    protected long getFramebufferId(RenderPass renderPass) {
        return this.renderpassToFramebufferMap.computeIfAbsent(renderPass, renderPass1 -> createFramebuffer(renderPass));
    }

    public VkViewport.Buffer viewport(MemoryStack stack) {
        VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
        viewport.x(0.0f);
        viewport.y(0.0f);
        viewport.width(this.width);
        viewport.height(this.height);
        viewport.minDepth(0.0f);
        viewport.maxDepth(1.0f);

        return viewport;
    }

    public VkRect2D.Buffer scissor(MemoryStack stack) {
        VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
        scissor.offset().set(0, 0);
        scissor.extent().set(this.width, this.height);

        return scissor;
    }

    public void cleanUp() {
        cleanUp(true);
    }

    public void cleanUp(boolean cleanImages) {
        if (cleanImages) {
            if (this.colorAttachment != null)
                this.colorAttachment.free();

            if (this.depthAttachment != null)
                this.depthAttachment.free();
        }

        if (renderpassToFramebufferMap.isEmpty()) {
            return;
        }

        final VkDevice device = Vulkan.getVkDevice();
        final var ids = renderpassToFramebufferMap.values().toLongArray();

        MemoryManager.getInstance().addFrameOp(
                () -> Arrays.stream(ids).forEach(id ->
                        vkDestroyFramebuffer(device, id, null))
        );

        renderpassToFramebufferMap.clear();
    }

    public void setLevel(int level) {
        int maxLevel = this.colorAttachment.mipLevels - 1;
        if (level > maxLevel) {
            throw new IllegalStateException(
                    "Requested mip level (%d) greater than color attachments max mip level (%d)"
                            .formatted(level, maxLevel));
        }

        this.level = level;
    }

    public boolean hasColorAttachment() {
        return this.colorAttachment != null;
    }

    public boolean hasDepthAttachment() {
        return this.depthAttachment != null;
    }

    public long getDepthImageView() {
        if (this.depthViewOverride != 0L) {
            return this.depthViewOverride;
        }

        return depthAttachment.getImageView();
    }

    public VulkanImage getDepthAttachment() {
        return depthAttachment;
    }

    public VulkanImage getColorAttachment() {
        return colorAttachment;
    }

    public long getColorAttachmentView() {
        if (this.colorViewOverride != 0L) {
            return this.colorViewOverride;
        }

        return colorAttachment.getLevelImageView(level);
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getFormat() {
        return this.format;
    }

    public int getDepthFormat() {
        return this.depthFormat;
    }

    public static Builder builder(int width, int height, int colorAttachments, boolean hasDepthAttachment) {
        return new Builder(width, height, colorAttachments, hasDepthAttachment);
    }

    public static Builder builder(VulkanImage colorAttachment, VulkanImage depthAttachment) {
        return new Builder(colorAttachment, depthAttachment);
    }

    public static class Builder {
        final String name;
        final boolean createImages;
        int width, height;
        int format, depthFormat;

        VulkanImage colorAttachment;
        VulkanImage depthAttachment;

        long colorView;
        long depthView;

        boolean hasColorAttachment;
        boolean hasDepthAttachment;

        boolean linearFiltering;
        boolean depthLinearFiltering;

        int level = 0;

        public Builder(int width, int height, int colorAttachments, boolean hasDepthAttachment) {
            this(null, width, height, colorAttachments, hasDepthAttachment);
        }

        public Builder(String name, int width, int height, int colorAttachments, boolean hasDepthAttachment) {
            Validate.isTrue(colorAttachments > 0 || hasDepthAttachment, "At least 1 attachment needed");

            //TODO multi color attachments
            Validate.isTrue(colorAttachments <= 1, "Not supported");

            this.name = name;
            this.createImages = true;
            this.format = DEFAULT_FORMAT;
            this.depthFormat = Vulkan.getDefaultDepthFormat();
            this.linearFiltering = true;
            this.depthLinearFiltering = false;

            this.width = width;
            this.height = height;
            this.hasColorAttachment = colorAttachments == 1;
            this.hasDepthAttachment = hasDepthAttachment;
        }

        public Builder(VulkanImage colorAttachment, VulkanImage depthAttachment) {
            Validate.isTrue(colorAttachment != null || depthAttachment != null, "At least 1 attachment needed");

            this.name = null;
            this.createImages = false;
            this.colorAttachment = colorAttachment;
            this.depthAttachment = depthAttachment;

            VulkanImage sizeSource = colorAttachment != null ? colorAttachment : depthAttachment;
            this.width = sizeSource.width;
            this.height = sizeSource.height;

            this.hasColorAttachment = colorAttachment != null;
            this.hasDepthAttachment = depthAttachment != null;

            this.format = this.hasColorAttachment ? colorAttachment.format : 0;
            this.depthFormat = this.hasDepthAttachment ? depthAttachment.format : 0;
            this.linearFiltering = true;
            this.depthLinearFiltering = false;
        }

        public Framebuffer build() {
            return new Framebuffer(this);
        }

        public Builder setLevel(int level) {
            this.level = level;

            return this;
        }

        /**
         * Use explicit image views (e.g. single mip level views) instead of the images' default views.
         */
        public Builder setAttachmentViews(long colorView, long depthView) {
            this.colorView = colorView;
            this.depthView = depthView;

            return this;
        }

        public Builder setSize(int width, int height) {
            this.width = width;
            this.height = height;

            return this;
        }

        public Builder setFormat(int format) {
            this.format = format;

            return this;
        }

        public Builder setLinearFiltering(boolean b) {
            this.linearFiltering = b;

            return this;
        }

        public Builder setDepthLinearFiltering(boolean b) {
            this.depthLinearFiltering = b;

            return this;
        }

    }
}

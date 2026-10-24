package net.vulkanmod.vulkan.framebuffer;

import com.mojang.renderpearl.api.device.GpuSurface;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.util.MathUtil;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static net.vulkanmod.vulkan.Vulkan.*;
import static net.vulkanmod.vulkan.util.VUtil.UINT32_MAX;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Owns the Vulkan swapchain for the game window.
 * <p>
 * Since Minecraft 26.3 the game renders into its own main render target and only blits the
 * result into the swapchain image, so the swapchain has no depth attachment of its own.
 */
public class SwapChain {
    private long swapChainId = VK_NULL_HANDLE;
    private List<VulkanImage> swapChainImages = new ArrayList<>();
    private int width;
    private int height;
    private int format;
    private int presentMode = VK_PRESENT_MODE_FIFO_KHR;
    public boolean isBGRAformat;

    public static int toVkPresentMode(GpuSurface.PresentMode presentMode) {
        return switch (presentMode) {
            case IMMEDIATE -> VK_PRESENT_MODE_IMMEDIATE_KHR;
            case MAILBOX -> VK_PRESENT_MODE_MAILBOX_KHR;
            case FIFO -> VK_PRESENT_MODE_FIFO_KHR;
            case FIFO_RELAXED -> VK_PRESENT_MODE_FIFO_RELAXED_KHR;
        };
    }

    public static Set<GpuSurface.PresentMode> querySupportedPresentModes() {
        Set<GpuSurface.PresentMode> modes = EnumSet.noneOf(GpuSurface.PresentMode.class);

        try (MemoryStack stack = stackPush()) {
            IntBuffer presentModes = DeviceManager.querySurfaceProperties(DeviceManager.physicalDevice, stack).presentModes;

            if (presentModes == null) {
                modes.add(GpuSurface.PresentMode.FIFO);
                return modes;
            }

            for (int i = 0; i < presentModes.capacity(); i++) {
                switch (presentModes.get(i)) {
                    case VK_PRESENT_MODE_IMMEDIATE_KHR -> modes.add(GpuSurface.PresentMode.IMMEDIATE);
                    case VK_PRESENT_MODE_MAILBOX_KHR -> modes.add(GpuSurface.PresentMode.MAILBOX);
                    case VK_PRESENT_MODE_FIFO_KHR -> modes.add(GpuSurface.PresentMode.FIFO);
                    case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> modes.add(GpuSurface.PresentMode.FIFO_RELAXED);
                    default -> {
                    }
                }
            }
        }

        // FIFO is the only mode that is required to be supported
        modes.add(GpuSurface.PresentMode.FIFO);
        return modes;
    }

    /**
     * (Re)creates the swapchain for the requested size and present mode.
     *
     * @return true if the swapchain has images, false if the surface is currently zero-sized.
     */
    public boolean create(int requestedWidth, int requestedHeight, int requestedPresentMode) {
        try (MemoryStack stack = stackPush()) {
            VkDevice device = Vulkan.getVkDevice();
            DeviceManager.SurfaceProperties surfaceProperties = DeviceManager.querySurfaceProperties(device.getPhysicalDevice(), stack);

            VkSurfaceFormatKHR surfaceFormat = getFormat(surfaceProperties.formats);
            this.presentMode = getPresentMode(surfaceProperties.presentModes, requestedPresentMode);
            VkExtent2D extent = getExtent(surfaceProperties.capabilities, requestedWidth, requestedHeight, stack);

            if (extent.width() == 0 || extent.height() == 0) {
                destroySwapChain(device);

                this.width = 0;
                this.height = 0;
                return false;
            }

            int requestedImages = Math.max(3, surfaceProperties.capabilities.minImageCount());
            if (surfaceProperties.capabilities.maxImageCount() > 0 && requestedImages > surfaceProperties.capabilities.maxImageCount()) {
                requestedImages = surfaceProperties.capabilities.maxImageCount();
            }

            IntBuffer imageCount = stack.ints(requestedImages);

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
            createInfo.surface(Vulkan.getSurface());

            // Image settings
            this.format = surfaceFormat.format();

            createInfo.minImageCount(requestedImages);
            createInfo.imageFormat(this.format);
            createInfo.imageColorSpace(surfaceFormat.colorSpace());
            createInfo.imageExtent(extent);
            createInfo.imageArrayLayers(1);
            createInfo.imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);

            Queue.QueueFamilyIndices indices = Queue.getQueueFamilies();

            if (indices.graphicsFamily != indices.presentFamily) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT);
                createInfo.pQueueFamilyIndices(stack.ints(indices.graphicsFamily, indices.presentFamily));
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            createInfo.preTransform(surfaceProperties.capabilities.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(this.presentMode);
            createInfo.clipped(true);
            createInfo.oldSwapchain(this.swapChainId);

            if (DeviceManager.fullScreenExclusiveControl) {
                VkSurfaceFullScreenExclusiveInfoEXT fullScreenExclusiveInfo = VkSurfaceFullScreenExclusiveInfoEXT.calloc(stack);
                fullScreenExclusiveInfo.sType(EXTFullScreenExclusive.VK_STRUCTURE_TYPE_SURFACE_FULL_SCREEN_EXCLUSIVE_INFO_EXT);
                fullScreenExclusiveInfo.fullScreenExclusive(EXTFullScreenExclusive.VK_FULL_SCREEN_EXCLUSIVE_DISALLOWED_EXT);
                createInfo.pNext(fullScreenExclusiveInfo.address());
            }

            LongBuffer pSwapChain = stack.longs(VK_NULL_HANDLE);

            int result = vkCreateSwapchainKHR(device, createInfo, null, pSwapChain);
            Vulkan.checkResult(result, "Failed to create swap chain");

            destroySwapChain(device);

            this.swapChainId = pSwapChain.get(0);

            vkGetSwapchainImagesKHR(device, this.swapChainId, imageCount, null);

            LongBuffer pSwapchainImages = stack.mallocLong(imageCount.get(0));

            vkGetSwapchainImagesKHR(device, this.swapChainId, imageCount, pSwapchainImages);

            this.swapChainImages = new ArrayList<>(imageCount.get(0));

            this.width = extent.width();
            this.height = extent.height();

            for (int i = 0; i < pSwapchainImages.capacity(); i++) {
                long imageId = pSwapchainImages.get(i);
                long imageView = VulkanImage.createImageView(imageId, this.format, VK_IMAGE_ASPECT_COLOR_BIT, 1, 1);

                VulkanImage image = new VulkanImage("Swapchain", imageId, this.format, 1, this.width, this.height, 4, 0, imageView);
                long samplerId = SamplerManager.getSampler(true, true, 0);
                image.setSampler(samplerId);
                this.swapChainImages.add(image);
            }

            return true;
        }
    }

    private void destroySwapChain(VkDevice device) {
        if (this.swapChainId != VK_NULL_HANDLE) {
            this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
            this.swapChainImages = new ArrayList<>();
            vkDestroySwapchainKHR(device, this.swapChainId, null);
            this.swapChainId = VK_NULL_HANDLE;
        }
    }

    public void cleanUp() {
        destroySwapChain(Vulkan.getVkDevice());
    }

    private VkSurfaceFormatKHR getFormat(VkSurfaceFormatKHR.Buffer availableFormats) {
        List<VkSurfaceFormatKHR> list = availableFormats.stream().toList();

        VkSurfaceFormatKHR format = list.get(0);

        for (VkSurfaceFormatKHR availableFormat : list) {
            if (availableFormat.format() == VK_FORMAT_R8G8B8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                return availableFormat;

            if (availableFormat.format() == VK_FORMAT_B8G8R8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                format = availableFormat;
            }
        }

        isBGRAformat = format.format() == VK_FORMAT_B8G8R8A8_UNORM;
        return format;
    }

    private static int getPresentMode(IntBuffer availablePresentModes, int requestedMode) {
        // FIFO mode is the only mode that has to be supported
        if (requestedMode == VK_PRESENT_MODE_FIFO_KHR || availablePresentModes == null)
            return VK_PRESENT_MODE_FIFO_KHR;

        for (int i = 0; i < availablePresentModes.capacity(); i++) {
            if (availablePresentModes.get(i) == requestedMode) {
                return requestedMode;
            }
        }

        Initializer.LOGGER.warn("Requested present mode not supported: {}: using FIFO present mode", getDisplayModeString(requestedMode));
        return VK_PRESENT_MODE_FIFO_KHR;
    }

    private static String getDisplayModeString(int requestedMode) {
        return switch (requestedMode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "Immediate";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "Mailbox (FastSync)";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO Relaxed (Adaptive VSync)";
            default -> "FIFO (VSync)";
        };
    }

    private static VkExtent2D getExtent(VkSurfaceCapabilitiesKHR capabilities, int requestedWidth, int requestedHeight, MemoryStack stack) {
        if (capabilities.currentExtent().width() != UINT32_MAX) {
            return capabilities.currentExtent();
        }

        int width = requestedWidth;
        int height = requestedHeight;

        if (width <= 0 || height <= 0) {
            IntBuffer pWidth = stack.ints(0);
            IntBuffer pHeight = stack.ints(0);

            SDLVideo.SDL_GetWindowSizeInPixels(window, pWidth, pHeight);
            width = pWidth.get(0);
            height = pHeight.get(0);
        }

        VkExtent2D actualExtent = VkExtent2D.malloc(stack).set(width, height);

        VkExtent2D minExtent = capabilities.minImageExtent();
        VkExtent2D maxExtent = capabilities.maxImageExtent();

        actualExtent.width(MathUtil.clamp(minExtent.width(), maxExtent.width(), actualExtent.width()));
        actualExtent.height(MathUtil.clamp(minExtent.height(), maxExtent.height(), actualExtent.height()));

        return actualExtent;
    }

    public long getId() {
        return this.swapChainId;
    }

    public List<VulkanImage> getImages() {
        return this.swapChainImages;
    }

    public VulkanImage getImage(int i) {
        return this.swapChainImages.get(i);
    }

    public boolean hasImages() {
        return this.swapChainId != VK_NULL_HANDLE && !this.swapChainImages.isEmpty();
    }

    public int getImagesNum() {
        return this.swapChainImages.size();
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

    public int getPresentMode() {
        return this.presentMode;
    }
}

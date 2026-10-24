package net.vulkanmod.render.engine;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * VulkanMod's implementation of the game's GPU device backend.
 */
public class VkGpuDevice implements GpuDeviceBackend {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final VkCommandEncoder encoder;
    private final DeviceInfo deviceInfo;
    private boolean closed;

    public VkGpuDevice() {
        VRenderSystem.initRenderer();
        Renderer.initRenderer();

        this.encoder = new VkCommandEncoder(this);
        this.deviceInfo = createDeviceInfo();

        LOGGER.info("VulkanMod backend initialized: {} ({})", this.deviceInfo.name(), this.deviceInfo.driverInfo());
    }

    private static DeviceInfo createDeviceInfo() {
        Device device = Vulkan.getDevice();
        VkPhysicalDeviceLimits limits = DeviceManager.deviceProperties().limits();

        long maxAllocationSize = device.getVk11Properties().maxMemoryAllocationSize();
        if (maxAllocationSize <= 0L) {
            maxAllocationSize = 1L << 30;
        }

        DeviceLimits deviceLimits = new DeviceLimits(
                Math.max(1, (int) limits.maxSamplerAnisotropy()),
                (int) limits.minUniformBufferOffsetAlignment(),
                limits.maxImageDimension2D(),
                maxAllocationSize,
                0,
                1,
                // Terrain is rendered by VulkanMod itself, so the game never needs its indirect path
                0
        );

        DeviceFeatures deviceFeatures = new DeviceFeatures(
                DeviceManager.fillModeNonSolid,
                DeviceManager.shaderDrawParameters,
                false,
                false,
                false,
                true,
                DeviceManager.drawIndirectFirstInstance,
                true
        );

        HintsAndWorkarounds hints = new HintsAndWorkarounds(false, false, false, false);

        DeviceType deviceType = switch (device.deviceType) {
            case VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> DeviceType.INTEGRATED;
            case VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> DeviceType.DISCRETE;
            case VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> DeviceType.VIRTUAL;
            case VK10.VK_PHYSICAL_DEVICE_TYPE_CPU -> DeviceType.CPU;
            default -> DeviceType.OTHER;
        };

        Set<String> extensions = DeviceManager.enabledExtensions() != null ? Set.copyOf(DeviceManager.enabledExtensions()) : Set.of();

        return new DeviceInfo(
                device.deviceName,
                device.vendorIdString,
                device.driverInfo(),
                true,
                "VulkanMod " + Initializer.getVersion(),
                limits.timestampPeriod(),
                deviceLimits,
                deviceFeatures,
                extensions,
                hints,
                deviceType
        );
    }

    @Override
    public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
        return new VkGpuSurface(windowHandle);
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return this.encoder;
    }

    @Override
    public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter,
                                    int maxAnisotropy, OptionalDouble maxLod) {
        return new VkSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(@Nullable String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        int vkFormat = VkConst.vkFormat(format);
        boolean cubemap = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;

        VulkanImage image = VulkanImage.builder(width, height)
                                       .setName(label)
                                       .setFormat(vkFormat)
                                       .setFormatSize(format.blockSize())
                                       .setArrayLayers(depthOrLayers)
                                       .setMipLevels(mipLevels)
                                       .setUsage(VkConst.imageUsage(usage, format))
                                       .setViewType(cubemap ? VK10.VK_IMAGE_VIEW_TYPE_CUBE : VK10.VK_IMAGE_VIEW_TYPE_2D)
                                       .createVulkanImage();

        // Textures rest in the shader read-only layout so they can be sampled without further transitions
        image.readOnlyLayout();

        return new VkGpuTexture(label, usage, format, width, height, depthOrLayers, mipLevels, image, true);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        return new VkTextureView((VkGpuTexture) texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size) {
        return new VkGpuBuffer(label, usage, size);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
        VkGpuBuffer buffer = new VkGpuBuffer(label, usage, data.remaining());
        this.encoder.writeToBuffer(buffer.slice(), data);
        return buffer;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return Collections.emptyList();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo pipelineCreateInfo) {
        try {
            VkRenderPipeline pipeline = new VkRenderPipeline(pipelineCreateInfo);
            return () -> pipeline;
        } catch (Exception e) {
            LOGGER.error("Couldn't compile pipeline ({})", pipelineCreateInfo.name(), e);
            return BackendRenderPipeline.Pending.NULL;
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;

        Vulkan.waitIdle();
        this.encoder.destroy();
        Vulkan.cleanUp();
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new VkQueryPool(size);
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0L;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }
}

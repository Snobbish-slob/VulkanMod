package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import it.unimi.dsi.fastutil.ints.Int2LongArrayMap;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Backend buffer: wraps a VulkanMod {@link Buffer}.
 */
public class VkGpuBuffer implements GpuBuffer {
    private final int usage;
    private final long size;
    @Nullable private final Supplier<String> label;
    private final Buffer buffer;
    private final boolean ownsBuffer;

    private final Int2LongArrayMap bufferViews = new Int2LongArrayMap(2);
    private boolean closed;

    public VkGpuBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
        this.usage = usage;
        this.size = size;
        this.label = label;

        MemoryType memoryType = VkConst.isMappable(usage) ? MemoryTypes.HOST_MEM : MemoryTypes.GPU_MEM;

        String name = label != null ? label.get() : null;
        this.buffer = new Buffer(name, VkConst.bufferUsage(usage), memoryType);
        this.buffer.createBuffer(size);
        this.ownsBuffer = true;
    }

    private VkGpuBuffer(Buffer buffer, int usage) {
        this.usage = usage;
        this.size = buffer.getBufferSize();
        this.label = null;
        this.buffer = buffer;
        this.ownsBuffer = false;
    }

    /**
     * Wraps an existing buffer without taking ownership.
     */
    public static VkGpuBuffer from(Buffer buffer, @GpuBuffer.Usage int usage) {
        return new VkGpuBuffer(buffer, usage);
    }

    @Override
    public long size() {
        return this.size;
    }

    @Override
    public int usage() {
        return this.usage;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        if (this.closed) {
            throw new IllegalStateException("Buffer already closed");
        }
        if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        }
        if (read && (this.usage & USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }
        if (write && (this.usage & USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }
        if (offset + length > this.size) {
            throw new IllegalArgumentException("Cannot map more data than this buffer can hold");
        }
        if (this.buffer.getDataPtr() == 0L) {
            throw new IllegalStateException("Buffer memory is not host visible");
        }

        ByteBuffer data = MemoryUtil.memByteBuffer(this.buffer.getDataPtr() + offset, (int) length);
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), data, () -> {});
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;

            if (!this.bufferViews.isEmpty()) {
                final long[] views = this.bufferViews.values().toLongArray();
                MemoryManager.getInstance().addFrameOp(() -> {
                    for (long view : views) {
                        vkDestroyBufferView(Vulkan.getVkDevice(), view, null);
                    }
                });
                this.bufferViews.clear();
            }

            if (this.ownsBuffer) {
                MemoryManager.getInstance().addToFreeable(this.buffer);
            }
        }
    }

    public Buffer getBuffer() {
        return this.buffer;
    }

    public long getId() {
        return this.buffer.getId();
    }

    /**
     * Buffer view used for uniform texel buffer bindings.
     */
    public long getBufferView(int vkFormat) {
        long view = this.bufferViews.get(vkFormat);
        if (view != 0L) {
            return view;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferViewCreateInfo createInfo = VkBufferViewCreateInfo.calloc(stack);
            createInfo.sType$Default();
            createInfo.buffer(this.buffer.getId());
            createInfo.format(vkFormat);
            createInfo.offset(0);
            createInfo.range(VK_WHOLE_SIZE);

            LongBuffer pView = stack.mallocLong(1);
            Vulkan.checkResult(vkCreateBufferView(Vulkan.getVkDevice(), createInfo, null, pView), "Failed to create buffer view");

            view = pView.get(0);
        }

        this.bufferViews.put(vkFormat, view);
        return view;
    }
}

package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Per-frame bump allocator for short-lived data.
 * <p>
 * Allocations live until the frame they were made in has finished executing on the GPU, which
 * is guaranteed by the frame fence wait in {@link Renderer#beginFrame()}.
 */
public class VkTransientMemory implements TransientMemory, Renderer.FrameListener {
    private static final long CHUNK_SIZE = 16L * 1024 * 1024;
    private static final int TRANSIENT_USAGE = GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX
                                               | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_INDIRECT_PARAMETERS;
    private static final int VK_USAGE = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                        | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT
                                        | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;

    private static final long UNIFORM_ALIGNMENT = DeviceManager.deviceProperties().limits().minUniformBufferOffsetAlignment();

    private final Renderer renderer;
    private FrameArena[] arenas;
    private int currentFrame;

    public VkTransientMemory(Renderer renderer) {
        this.renderer = renderer;
        this.arenas = createArenas(Renderer.getFramesNum());
        renderer.addFrameListener(this);
    }

    private static FrameArena[] createArenas(int frames) {
        FrameArena[] arenas = new FrameArena[frames];
        for (int i = 0; i < frames; i++) {
            arenas[i] = new FrameArena();
        }
        return arenas;
    }

    @Override
    public void onBeginFrame(int frame) {
        this.currentFrame = frame;
        this.arenas[frame].reset();
    }

    @Override
    public void onFramesNumChanged(int framesNum) {
        for (FrameArena arena : this.arenas) {
            arena.free();
        }
        this.arenas = createArenas(framesNum);
        this.currentFrame = 0;
    }

    public void destroy() {
        this.renderer.removeFrameListener(this);
        for (FrameArena arena : this.arenas) {
            arena.free();
        }
    }

    private FrameArena arena() {
        this.renderer.ensureRecording();
        return this.arenas[this.currentFrame];
    }

    private static long alignUp(long value, long alignment) {
        return alignment <= 1 ? value : (value + alignment - 1) & -alignment;
    }

    private GpuBufferSlice allocate(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        long allocSize = Math.max(size, minimumAllocation);
        if (elementSize > 1) {
            allocSize = alignUp(allocSize, elementSize);
        }

        // Uniform slices must respect the device offset alignment, other data at least 16 bytes
        long align = Math.max(Math.max(alignment, 16L), elementSize > 0 ? elementSize : 1L);
        if ((usage & GpuBuffer.USAGE_UNIFORM) != 0) {
            align = Math.max(align, UNIFORM_ALIGNMENT);
        }

        return arena().allocate(allocSize, align, size);
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
        long allocSize = Math.max(size, minimumAllocation);
        if (elementSize > 1) {
            allocSize = alignUp(allocSize, elementSize);
        }

        return arena().allocateCpu(allocSize, Math.max(alignment, 16L), size);
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        GpuBufferSlice slice = allocate(size, alignment, usage, minimumAllocation, elementSize);
        return new GpuBufferSlice.MappedView(slice, map(slice), () -> {});
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        return allocate(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        GpuBufferSlice slice = allocate(size, alignment, usage, minimumAllocation, elementSize);
        return new GpuBufferSlice.MappedView(slice, map(slice), () -> {});
    }

    private GpuBufferSlice upload(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        long totalSize = 0L;
        for (ByteBuffer buffer : data) {
            totalSize += buffer.remaining();
        }

        GpuBufferSlice slice = allocate(totalSize, alignment, usage, minimumAllocation, elementSize);
        ByteBuffer dst = map(slice);

        for (ByteBuffer src : data) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(src), MemoryUtil.memAddress(dst), src.remaining());
            dst.position(dst.position() + src.remaining());
        }

        return slice;
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
        List<GpuBufferSlice> slices = new ArrayList<>(data.size());
        for (ByteBuffer buffer : data) {
            slices.add(upload(List.of(buffer), alignment, usage, buffer.remaining(), 1L));
        }
        return slices;
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
        return multiUploadStaging(data, alignment, usage);
    }

    private static ByteBuffer map(GpuBufferSlice slice) {
        VkGpuBuffer buffer = (VkGpuBuffer) slice.buffer();
        return MemoryUtil.memByteBuffer(buffer.getBuffer().getDataPtr() + slice.offset(), (int) slice.length());
    }

    /**
     * Host visible buffers of one frame slot, used as a bump allocator.
     */
    private static class FrameArena {
        private final List<Buffer> buffers = new ObjectArrayList<>();
        private final List<VkGpuBuffer> gpuBuffers = new ObjectArrayList<>();
        private final LongArrayList cpuAllocations = new LongArrayList();
        private int currentBuffer = -1;
        private long offset;

        GpuBufferSlice allocate(long allocSize, long alignment, long sliceSize) {
            if (this.currentBuffer >= 0) {
                Buffer buffer = this.buffers.get(this.currentBuffer);
                long alignedOffset = alignUp(this.offset, alignment);

                if (alignedOffset + allocSize <= buffer.getBufferSize()) {
                    this.offset = alignedOffset + allocSize;
                    return new GpuBufferSlice(this.gpuBuffers.get(this.currentBuffer), alignedOffset, sliceSize);
                }
            }

            // Try the remaining buffers before creating a new one
            for (int i = this.currentBuffer + 1; i < this.buffers.size(); i++) {
                if (this.buffers.get(i).getBufferSize() >= allocSize) {
                    this.currentBuffer = i;
                    this.offset = allocSize;
                    return new GpuBufferSlice(this.gpuBuffers.get(i), 0L, sliceSize);
                }
            }

            long bufferSize = Math.max(CHUNK_SIZE, alignUp(allocSize, 4096L));
            Buffer buffer = new Buffer("Transient memory", VK_USAGE, MemoryTypes.HOST_MEM);
            buffer.createBuffer(bufferSize);

            this.buffers.add(buffer);
            this.gpuBuffers.add(VkGpuBuffer.from(buffer, TRANSIENT_USAGE | GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE));
            this.currentBuffer = this.buffers.size() - 1;
            this.offset = allocSize;

            return new GpuBufferSlice(this.gpuBuffers.get(this.currentBuffer), 0L, sliceSize);
        }

        ByteBuffer allocateCpu(long allocSize, long alignment, long size) {
            long ptr = MemoryUtil.nmemAlignedAlloc(alignment, allocSize);
            if (ptr == 0L) {
                throw new OutOfMemoryError("Failed to allocate %d bytes of transient memory".formatted(allocSize));
            }

            this.cpuAllocations.add(ptr);
            return MemoryUtil.memByteBuffer(ptr, (int) size);
        }

        void reset() {
            this.currentBuffer = this.buffers.isEmpty() ? -1 : 0;
            this.offset = 0L;

            for (int i = 0; i < this.cpuAllocations.size(); i++) {
                MemoryUtil.nmemAlignedFree(this.cpuAllocations.getLong(i));
            }
            this.cpuAllocations.clear();
        }

        void free() {
            this.reset();

            for (Buffer buffer : this.buffers) {
                buffer.scheduleFree();
            }
            this.buffers.clear();
            this.gpuBuffers.clear();
            this.currentBuffer = -1;
        }
    }
}

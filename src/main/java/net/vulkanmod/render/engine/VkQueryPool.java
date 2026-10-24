package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;
import java.util.OptionalLong;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Timestamp query pool.
 */
public class VkQueryPool implements GpuQueryPool {
    private final int size;
    private final long pool;
    private boolean closed;

    public VkQueryPool(int size) {
        this.size = size;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkQueryPoolCreateInfo createInfo = VkQueryPoolCreateInfo.calloc(stack);
            createInfo.sType$Default();
            createInfo.queryType(VK_QUERY_TYPE_TIMESTAMP);
            createInfo.queryCount(size);

            LongBuffer pPool = stack.mallocLong(1);
            Vulkan.checkResult(vkCreateQueryPool(Vulkan.getVkDevice(), createInfo, null, pPool), "Failed to create query pool");
            this.pool = pPool.get(0);
        }

        // Queries have to be reset before their first use
        VK12.vkResetQueryPool(Vulkan.getVkDevice(), this.pool, 0, size);
    }

    public void writeTimestamp(VkCommandBuffer commandBuffer, int index) {
        VK12.vkResetQueryPool(Vulkan.getVkDevice(), this.pool, index, 1);
        vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, this.pool, index);
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public OptionalLong getValue(int index) {
        return getValues(index, 1)[0];
    }

    @Override
    public OptionalLong[] getValues(int index, int count) {
        OptionalLong[] values = new OptionalLong[count];

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Each query returns the value followed by its availability flag
            LongBuffer results = stack.callocLong(count * 2);
            int result = vkGetQueryPoolResults(Vulkan.getVkDevice(), this.pool, index, count, results,
                                               Long.BYTES * 2, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);

            for (int i = 0; i < count; i++) {
                boolean available = result == VK_SUCCESS && results.get(i * 2 + 1) != 0L;
                values[i] = available ? OptionalLong.of(results.get(i * 2)) : OptionalLong.empty();
            }
        }

        return values;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            final long pool = this.pool;
            MemoryManager.getInstance().addFrameOp(() -> vkDestroyQueryPool(Vulkan.getVkDevice(), pool, null));
        }
    }
}

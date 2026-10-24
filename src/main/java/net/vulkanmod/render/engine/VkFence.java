package net.vulkanmod.render.engine;

import com.mojang.renderpearl.api.commands.GpuFence;
import net.vulkanmod.vulkan.Renderer;

/**
 * Backend fence: completes once the frame that was being recorded when the fence was created
 * has finished executing.
 */
public class VkFence implements GpuFence {
    private final long serial;
    private boolean completed;

    public VkFence() {
        this.serial = Renderer.getInstance().getCurrentSerial();
    }

    @Override
    public boolean awaitCompletion(long timeoutNS) {
        if (!this.completed) {
            this.completed = Renderer.getInstance().awaitSerial(this.serial, timeoutNS);
        }

        return this.completed;
    }

    @Override
    public void close() {
        this.completed = true;
    }
}

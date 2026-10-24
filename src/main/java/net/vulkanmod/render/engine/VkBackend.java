package net.vulkanmod.render.engine;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import net.vulkanmod.vulkan.Vulkan;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.VK;
import org.slf4j.Logger;

import java.util.Objects;

/**
 * Entry point of VulkanMod's GPU backend, selected through {@code PreferredGraphicsApi}.
 */
public class VkBackend implements GpuBackend {
    private static final Logger LOGGER = LogUtils.getLogger();

    private boolean libraryLoaded;

    @Override
    public String getName() {
        return "VulkanMod";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        if (this.libraryLoaded) {
            return;
        }

        FunctionProvider functionProvider;
        try {
            functionProvider = VK.getFunctionProvider();
        } catch (Throwable t) {
            throw new BackendCreationException("Vulkan loader library is missing: " + t.getMessage(), BackendCreationException.Reason.VULKAN_LOADER_MISSING);
        }

        boolean loaded;
        if (functionProvider instanceof SharedLibrary sharedLibrary && sharedLibrary.getPath() != null) {
            loaded = SDLVulkan.SDL_Vulkan_LoadLibrary(sharedLibrary.getPath());
        }
        else {
            loaded = SDLVulkan.SDL_Vulkan_LoadLibrary((CharSequence) null);
        }

        if (!loaded) {
            throw new BackendCreationException(
                    "Vulkan is not supported: " + Objects.requireNonNullElse(SDLError.SDL_GetError(), "<no error>"),
                    BackendCreationException.Reason.PLATFORM_ERROR
            );
        }

        this.libraryLoaded = true;
    }

    @Override
    public void unloadLibrary() {
        if (this.libraryLoaded) {
            SDLVulkan.SDL_Vulkan_UnloadLibrary();
            this.libraryLoaded = false;
        }
    }

    @Override
    public long createWindow(@Nullable String title, int width, int height, long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_VULKAN | flags);
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
        try {
            Vulkan.initVulkan();
            return new FrontendGpuDevice(new VkGpuDevice());
        } catch (Exception e) {
            LOGGER.error("Failed to initialize VulkanMod", e);
            throw new BackendCreationException("Failed to initialize VulkanMod: " + e.getMessage(), BackendCreationException.Reason.OTHER);
        }
    }
}

package net.vulkanmod.config.video;

import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Enumerates display modes through SDL3 and applies the selected mode through the vanilla Window,
 * which owns fullscreen handling in Minecraft 26.3.
 */
public abstract class VideoModeManager {
    public static Int2ObjectMap<VideoModeSet[]> monitorToVideoModeSets = new Int2ObjectOpenHashMap<>();
    public static Int2ObjectMap<VideoModeSet.VideoMode> osVideoModes = new Int2ObjectOpenHashMap<>();

    public static int selectedMonitor;
    public static VideoModeSet.VideoMode selectedVideoMode;

    public static void refresh() {
        monitorToVideoModeSets.clear();
        osVideoModes.clear();

        IntBuffer displays = SDLVideo.SDL_GetDisplays();
        if (displays != null) {
            try {
                for (int i = 0; i < displays.limit(); i++) {
                    addMonitorVideoModes(displays.get(i));
                }
            } finally {
                SDLStdinc.SDL_free(displays);
            }
        }
    }

    public static void addMonitorVideoModes(int displayId) {
        monitorToVideoModeSets.put(displayId, getVideoResolutions(displayId));
        VideoModeSet.VideoMode current = getCurrentVideoMode(displayId);
        if (current != null) {
            osVideoModes.put(displayId, current);
        }
    }

    public static void removeMonitor(int displayId) {
        monitorToVideoModeSets.remove(displayId);
        osVideoModes.remove(displayId);
    }

    public static void applySelectedVideoMode() {
        Initializer.CONFIG.videoMode = selectedVideoMode;

        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        VideoMode videoMode = toVanilla(selectedVideoMode);

        minecraft.options.fullscreenVideoModeString = videoMode.write();
        window.setPreferredFullscreenVideoMode(Optional.of(videoMode));
        window.changeFullscreenVideoMode();
    }

    private static VideoMode toVanilla(VideoModeSet.VideoMode videoMode) {
        int bits = videoMode.bitDepth > 0 ? videoMode.bitDepth : 8;
        return new VideoMode(videoMode.width, videoMode.height, bits, bits, bits, videoMode.refreshRate);
    }

    public static VideoModeSet[] getVideoResolutions() {
        VideoModeSet[] sets = monitorToVideoModeSets.get(selectedMonitor);
        return sets != null ? sets : new VideoModeSet[] { VideoModeSet.getDummy() };
    }

    public static VideoModeSet getFirstAvailable() {
        if (monitorToVideoModeSets.isEmpty()) {
            refresh();
        }

        var videoModeSets = monitorToVideoModeSets.get(SDLVideo.SDL_GetPrimaryDisplay());

        if (videoModeSets == null && !monitorToVideoModeSets.isEmpty()) {
            videoModeSets = monitorToVideoModeSets.values().iterator().next();
        }

        if (videoModeSets != null && videoModeSets.length > 0)
            return videoModeSets[videoModeSets.length - 1];
        else
            return VideoModeSet.getDummy();
    }

    public static VideoModeSet.VideoMode getOsVideoMode() {
        return osVideoModes.get(selectedMonitor);
    }

    public static VideoModeSet.VideoMode getCurrentVideoMode(int displayId) {
        SDL_DisplayMode mode = SDLVideo.SDL_GetDesktopDisplayMode(displayId);
        if (mode == null)
            return null;

        return new VideoModeSet.VideoMode(mode.w(), mode.h(), 8, Math.round(mode.refresh_rate()));
    }

    public static VideoModeSet[] getVideoResolutions(int displayId) {
        List<VideoModeSet> videoModeSets = new ArrayList<>();

        PointerBuffer modes = SDLVideo.SDL_GetFullscreenDisplayModes(displayId);
        if (modes != null) {
            try {
                int currWidth = 0, currHeight = 0;
                VideoModeSet videoModeSet = null;

                for (int i = modes.limit() - 1; i >= 0; i--) {
                    SDL_DisplayMode mode = SDL_DisplayMode.create(modes.get(i));

                    int width = mode.w();
                    int height = mode.h();
                    int refreshRate = Math.round(mode.refresh_rate());

                    if (currWidth != width || currHeight != height) {
                        currWidth = width;
                        currHeight = height;

                        videoModeSet = new VideoModeSet(currWidth, currHeight, 8);
                        videoModeSets.add(videoModeSet);
                    }

                    if (!videoModeSet.hasRefreshRate(refreshRate)) {
                        videoModeSet.addRefreshRate(refreshRate);
                    }
                }
            } finally {
                SDLStdinc.SDL_free(modes);
            }
        }

        if (videoModeSets.isEmpty()) {
            VideoModeSet.VideoMode current = getCurrentVideoMode(displayId);
            if (current != null) {
                VideoModeSet set = new VideoModeSet(current.width, current.height, current.bitDepth);
                set.addRefreshRate(current.refreshRate);
                videoModeSets.add(set);
            }
        }

        VideoModeSet[] arr = new VideoModeSet[videoModeSets.size()];
        videoModeSets.toArray(arr);
        return arr;
    }

    public static VideoModeSet getVideoModeSet(VideoModeSet.VideoMode videoMode) {
        var videoModeSets = monitorToVideoModeSets.get(selectedMonitor);
        if (videoModeSets == null)
            return null;

        for (var set : videoModeSets) {
            if (set.width == videoMode.width && set.height == videoMode.height)
                return set;
        }

        return null;
    }

    public static void selectBestMonitor(Window window) {
        refresh();

        selectedMonitor = SDLVideo.SDL_GetDisplayForWindow(window.handle());

        if (selectedMonitor == 0) {
            // Fallback to primary in case of null handle
            selectedMonitor = SDLVideo.SDL_GetPrimaryDisplay();
        }
    }

    public static void checkConfigVideoMode(Config config) {
        var videoMode = config.videoMode;

        if (videoMode.width <= 0 || videoMode.height <= 0 || videoMode.refreshRate <= 0) {
            videoMode = getFirstAvailable().getVideoMode();
            config.videoMode = videoMode;
            config.write();
        }
    }
}

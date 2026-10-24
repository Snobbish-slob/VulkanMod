package net.vulkanmod.config;

import net.minecraft.util.Util;
import org.apache.commons.lang3.SystemUtils;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;

import static net.vulkanmod.Initializer.CONFIG;
import static net.vulkanmod.Initializer.LOGGER;

/**
 * Platform detection. Minecraft 26.3 creates its window through SDL3; the only thing VulkanMod has
 * to decide before SDL is initialised is which Linux video driver (Wayland or X11) SDL should use.
 */
public abstract class Platform {
    public static final Util.OS OS = Util.getPlatform();

    private static String requestedDriver;
    private static String activeDE;

    public static void init() {
        requestedDriver = getRequestedVideoDriver();
        activeDE = determineDE();

        if (requestedDriver != null) {
            // Must be set before RenderSystem.initBackendSystem() calls SDL_Init
            SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_DRIVER, requestedDriver);
        }

        LOGGER.info("Selecting Platform: {}", requestedDriver != null ? requestedDriver.toUpperCase() : getStringFromPlat());
    }

    // Actually detect the currently active Display Server (if both Wayland and X11 are present on the system)
    private static String determineLinuxDisplayServer() {
        String xdgSessionType = System.getenv("XDG_SESSION_TYPE");

        if (xdgSessionType == null) return null; // Likely Android

        return switch (xdgSessionType) {
            case "wayland" -> CONFIG.useWayland ? "wayland" : "x11";
            case "x11" -> "x11";
            default -> null; //Either unknown Platform or Display Server
        };
    }

    private static String getRequestedVideoDriver() {
        if (SystemUtils.IS_OS_LINUX) return determineLinuxDisplayServer(); //Linux Or Android
        return null; // Let SDL pick on Windows, macOS and unknown platforms
    }

    private static String getStringFromPlat() {
        if (SystemUtils.IS_OS_WINDOWS) return "WIN32";
        if (SystemUtils.IS_OS_MAC_OSX) return "MACOS";
        if (SystemUtils.IS_OS_LINUX) return "LINUX";
        return "UNKNOWN";
    }

    private static String determineDE() {
        String xdgSessionDesktop = System.getenv("XDG_SESSION_DESKTOP");
        String xdgCurrentDesktop = System.getenv("XDG_CURRENT_DESKTOP");

        if (xdgSessionDesktop != null)
            return xdgSessionDesktop.toLowerCase();

        if (xdgCurrentDesktop != null)
            return xdgCurrentDesktop.toLowerCase();

        return "N/A";
    }

    /** Name of the SDL video driver in use, or the requested one before SDL is initialised. */
    public static String getVideoDriver() {
        String current = SDLVideo.SDL_GetCurrentVideoDriver();
        return current != null ? current : requestedDriver;
    }

    public static boolean isWindows() {
        return OS == Util.OS.WINDOWS;
    }

    public static boolean isLinux() {
        return OS == Util.OS.LINUX;
    }

    public static boolean isMacOS() {
        return OS == Util.OS.OSX;
    }

    public static boolean isAndroid() {
        return SystemUtils.IS_OS_LINUX && System.getenv("XDG_SESSION_TYPE") == null && requestedDriver == null;
    }

    //Allows platform specific checks to be handled
    public static boolean isWayLand() {
        return "wayland".equals(getVideoDriver());
    }

    public static boolean isX11() {
        return "x11".equals(getVideoDriver());
    }

    //Desktop Environment Names: https://wiki.archlinux.org/title/Xdg-utils#Usage
    public static boolean isGnome() {
        return activeDE.contains("gnome");
    }

    public static boolean isWeston() {
        return activeDE.contains("weston");
    }

    public static boolean isGeneric() {
        return activeDE.contains("generic");
    }
}

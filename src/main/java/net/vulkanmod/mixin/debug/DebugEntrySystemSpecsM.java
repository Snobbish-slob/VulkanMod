package net.vulkanmod.mixin.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Locale;

@Mixin(DebugEntrySystemSpecs.class)
public class DebugEntrySystemSpecsM {
    @Shadow @Final private static Identifier GROUP;

    @Inject(method = "display", at = @At("HEAD"), cancellable = true)
    private void display(DebugScreenDisplayer debugScreenDisplayer, Level level, LevelChunk levelChunk,
                        LevelChunk levelChunk2, CallbackInfo ci) {
        DeviceInfo deviceInfo = RenderSystem.getDevice().getDeviceInfo();
        debugScreenDisplayer.addToGroup(
                GROUP,
                List.of(
                        String.format(Locale.ROOT, "Java: %s", System.getProperty("java.version")),
                        String.format(Locale.ROOT, "CPU: %s", DebugEntrySystemSpecs.getCpuInfo()),
                        String.format(
                                Locale.ROOT, "Display: %dx%d (%s)", Minecraft.getInstance().getWindow().getWidth(), Minecraft.getInstance().getWindow().getHeight(), deviceInfo.vendorName()
                        ),
                        deviceInfo.name(),
                        String.format(Locale.ROOT, "%s %s", deviceInfo.backendName(), deviceInfo.driverInfo()),
                        String.format(Locale.ROOT, "VulkanMod %s", Initializer.getVersion())
                )
        );
        ci.cancel();
    }
}

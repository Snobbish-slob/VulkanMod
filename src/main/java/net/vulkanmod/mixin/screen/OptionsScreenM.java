package net.vulkanmod.mixin.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.VOptionScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Supplier;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenM extends Screen {
    @Shadow @Final private static Component VIDEO;

    @Shadow protected abstract Button openScreenButton(Component message, Supplier<Screen> screenToScreen);

    protected OptionsScreenM(Component title) {
        super(title);
    }

    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/options/OptionsScreen;openScreenButton(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;"))
    private Button injectVideoOptionScreen(OptionsScreen instance, Component message, Supplier<Screen> screenToScreen) {
        if (message == VIDEO) {
            return this.openScreenButton(message, () -> new VOptionScreen(Component.literal("Video Setting"), this));
        }
        return this.openScreenButton(message, screenToScreen);
    }
}

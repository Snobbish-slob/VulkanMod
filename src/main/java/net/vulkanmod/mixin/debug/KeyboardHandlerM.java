package net.vulkanmod.mixin.debug;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerM {

    @Shadow protected abstract boolean handleChunkDebugKeys(KeyEvent keyEvent);

    @Shadow private boolean usedDebugKeyAsModifier;

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void chunkDebug(long handle, int action, KeyEvent keyEvent, CallbackInfo ci) {
        // F7 + chunk debug key (e.g. U -> capture frustum), independent of the vanilla debug hotkey flag
        if (action == InputConstants.PRESS && InputConstants.isKeyDown(InputConstants.KEY_F7)) {
            this.usedDebugKeyAsModifier |= this.handleChunkDebugKeys(keyEvent);
        }
    }
}

package dev.moonbridge.clientws.mixins.early;

import net.minecraft.client.multiplayer.GuiConnecting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.moonbridge.clientws.net.WebSocketConnectContext;

/**
 * {@code GuiConnecting.func_146367_a} creates and starts the "Server Connector" thread, which inherits the ws URL the
 * constructor's address parse recorded on the client thread. Once that thread exists the client thread's copy is
 * dropped, so it cannot leak into a later connection or into other threads created from the client thread.
 */
@Mixin(GuiConnecting.class)
public abstract class MixinGuiConnecting {

    @Inject(method = "func_146367_a", at = @At("RETURN"))
    private void moonbridgews$clearClientThread(String host, int port, CallbackInfo ci) {
        WebSocketConnectContext.clear();
    }
}

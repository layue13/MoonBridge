package dev.moonbridge.clientws.mixins.early;

import net.minecraft.client.multiplayer.ServerAddress;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.moonbridge.clientws.bridge.Log;
import dev.moonbridge.clientws.net.WebSocketAddress;
import dev.moonbridge.clientws.net.WebSocketConnectContext;

/**
 * {@code ServerAddress.func_78860_a} is the address parser used by both join ({@code GuiConnecting}) and the server
 * list ping ({@code OldServerPinger}). For a ws/wss URL it returns the URL's host and port (so DNS resolves the URL
 * host and no SRV lookup happens) and records the full URL for the {@code provideLanClient} call that follows. Any
 * other
 * address parses exactly as vanilla, and clears a URL left over from an earlier parse on this thread.
 */
@Mixin(ServerAddress.class)
public abstract class MixinServerAddress {

    @Inject(method = "func_78860_a", at = @At("HEAD"), cancellable = true)
    private static void moonbridgews$parseWebSocket(String address, CallbackInfoReturnable<ServerAddress> cir) {
        if (!WebSocketAddress.looksLikeWebSocket(address)) {
            WebSocketConnectContext.clear();
            return;
        }
        WebSocketAddress ws;
        try {
            ws = WebSocketAddress.parse(address);
        } catch (IllegalArgumentException e) {
            Log.LOG.warn("Invalid WebSocket server address '{}': {}", address, e.getMessage());
            WebSocketConnectContext.clear();
            // Unresolvable on purpose: the GUI then reports an unknown host instead of dialing "ws" over TCP.
            cir.setReturnValue(ServerAddressInvoker.moonbridgews$create(address.trim(), 25565));
            return;
        }
        WebSocketConnectContext.set(ws);
        cir.setReturnValue(ServerAddressInvoker.moonbridgews$create(ws.host(), ws.port()));
    }
}

package dev.moonbridge.clientws.mixins.early;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.OldServerPinger;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.moonbridge.clientws.net.WebSocketAddress;

/**
 * {@code OldServerPinger.func_147225_b} is the pre-1.7 (0xFE) fallback ping that vanilla tries when the normal status
 * ping fails. It dials raw TCP with its own bootstrap, which cannot reach a WebSocket endpoint, so it is skipped for
 * ws/wss entries; the entry keeps vanilla's "Can't connect to server" text.
 */
@Mixin(OldServerPinger.class)
public abstract class MixinOldServerPinger {

    @Inject(method = "func_147225_b", at = @At("HEAD"), cancellable = true)
    private void moonbridgews$skipLegacyPing(ServerData server, CallbackInfo ci) {
        if (WebSocketAddress.looksLikeWebSocket(server.serverIP)) {
            ci.cancel();
        }
    }
}

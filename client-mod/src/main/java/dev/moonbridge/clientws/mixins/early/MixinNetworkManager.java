package dev.moonbridge.clientws.mixins.early;

import java.net.InetAddress;

import net.minecraft.network.NetworkManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import dev.moonbridge.clientws.bridge.WebSocketTarget;
import dev.moonbridge.clientws.net.WebSocketAddress;
import dev.moonbridge.clientws.net.WebSocketConnectContext;

/**
 * In {@code provideLanClient} (srg {@code func_150726_a}), right after the {@code NetworkManager} is constructed and
 * still on the calling thread, takes the ws URL recorded by the address parse for this exact host and port and stores
 * it on the instance. The channel initializer reads it later on the event loop (see
 * {@link MixinNetworkManagerChannelInitializer}), so no thread-local is read off-thread.
 */
@Mixin(NetworkManager.class)
public abstract class MixinNetworkManager implements WebSocketTarget {

    @Unique
    private volatile WebSocketAddress moonbridgews$webSocket;

    @ModifyVariable(method = "provideLanClient", at = @At("STORE"), ordinal = 0)
    private static NetworkManager moonbridgews$attachWebSocket(NetworkManager manager, InetAddress address, int port) {
        WebSocketAddress ws = WebSocketConnectContext.take(address, port);
        if (ws != null) {
            ((WebSocketTarget) manager).moonbridgews$setWebSocket(ws);
        }
        return manager;
    }

    @Override
    public WebSocketAddress moonbridgews$getWebSocket() {
        return moonbridgews$webSocket;
    }

    @Override
    public void moonbridgews$setWebSocket(WebSocketAddress address) {
        moonbridgews$webSocket = address;
    }
}

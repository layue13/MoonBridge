package dev.moonbridge.clientws.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.moonbridge.clientws.bridge.Log;
import dev.moonbridge.clientws.bridge.WebSocketTarget;
import dev.moonbridge.clientws.net.WebSocketAddress;
import dev.moonbridge.clientws.net.WebSocketPipeline;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

/**
 * The anonymous {@code ChannelInitializer} of {@code NetworkManager.provideLanClient}: {@code NetworkManager$2}
 * (obfuscated {@code el}, checked against the 1.7.10 client jar and Forge's notch-mcp.srg). After vanilla has added
 * timeout/splitter/decoder/prepender/encoder/packet_handler, the WebSocket handlers go in front of them if the
 * connection's {@code NetworkManager} (the {@code packet_handler}) carries a ws URL.
 */
@Mixin(targets = "net.minecraft.network.NetworkManager$2")
public abstract class MixinNetworkManagerChannelInitializer {

    @Inject(method = "initChannel", at = @At("TAIL"), remap = false)
    private void moonbridgews$installWebSocket(Channel channel, CallbackInfo ci) {
        ChannelHandler handler = channel.pipeline()
            .get("packet_handler");
        if (!(handler instanceof WebSocketTarget)) {
            return;
        }
        WebSocketAddress ws = ((WebSocketTarget) handler).moonbridgews$getWebSocket();
        if (ws != null) {
            Log.LOG.info("Connecting over WebSocket to {}", ws);
            WebSocketPipeline.install(channel.pipeline(), ws);
        }
    }
}

package dev.moonbridge.core.websocket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Front of a player connection that arrives over WebSocket (optionally TLS). After the upgrade it leaves
 * only the WebSocket codec and {@link WebSocketFrameAdapter} ahead of the normal Minecraft pipeline, which
 * the caller installs from the ready callback; everything behind the adapter sees the same byte stream as
 * a direct TCP connection.
 */
public final class WebSocketFrontend {
    public static final String FRAME_ADAPTER = "websocket-frames";
    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketFrontend.class);
    private static final int MAX_UPGRADE_REQUEST_BYTES = 8192;
    // One Minecraft 1.7.10 frame (three-byte length plus at most 0x1FFFFF bytes) fits in one message.
    private static final int MAX_FRAME_PAYLOAD = 0x1FFFFF + 3;

    /** Immutable listener settings; a null TLS context means plain ws:// (TLS ends at a CDN or proxy). */
    public record Settings(String path, SslContext tls, ClientAddressPolicy addresses, Duration handshakeTimeout) {
        public Settings {
            Objects.requireNonNull(path, "path");
            if (!path.startsWith("/")) throw new IllegalArgumentException("websocket path must start with /");
            Objects.requireNonNull(addresses, "addresses");
            Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
            if (handshakeTimeout.isNegative() || handshakeTimeout.isZero()) {
                throw new IllegalArgumentException("handshake timeout must be positive");
            }
        }
    }

    private WebSocketFrontend() { }

    /**
     * Installs the upgrade handlers on a newly accepted channel. {@code ready} runs on the channel's event
     * loop once the upgrade succeeded, with the player address chosen by the settings' policy.
     */
    public static void install(Channel channel, Settings settings, BiConsumer<Channel, InetSocketAddress> ready) {
        ChannelPipeline pipeline = channel.pipeline();
        if (settings.tls() != null) pipeline.addLast("websocket-tls", settings.tls().newHandler(channel.alloc()));
        pipeline.addLast("http-codec", new HttpServerCodec());
        pipeline.addLast("http-aggregator", new HttpObjectAggregator(MAX_UPGRADE_REQUEST_BYTES));
        pipeline.addLast("websocket-protocol", new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(settings.path())
                .checkStartsWith(false)
                .handleCloseFrames(true)
                .handshakeTimeoutMillis(settings.handshakeTimeout().toMillis())
                .decoderConfig(WebSocketDecoderConfig.newBuilder()
                        .maxFramePayloadLength(MAX_FRAME_PAYLOAD)
                        .allowExtensions(false)
                        .build())
                .build()));
        pipeline.addLast("websocket-upgrade", new Upgrade(settings, ready));
    }

    /** Waits for the upgrade, rejects other HTTP requests and bounds the time before the request arrives. */
    private static final class Upgrade extends ChannelInboundHandlerAdapter {
        private final Settings settings;
        private final BiConsumer<Channel, InetSocketAddress> ready;
        private ScheduledFuture<?> deadline;

        Upgrade(Settings settings, BiConsumer<Channel, InetSocketAddress> ready) {
            this.settings = settings;
            this.ready = ready;
        }

        @Override public void handlerAdded(ChannelHandlerContext ctx) {
            // Netty's handshake timeout starts only once a request arrives; this one covers an idle socket.
            deadline = ctx.executor().schedule(() -> ctx.close(), settings.handshakeTimeout().toNanos(),
                    TimeUnit.NANOSECONDS);
        }

        @Override public void handlerRemoved(ChannelHandlerContext ctx) { cancel(); }

        @Override public void channelInactive(ChannelHandlerContext ctx) {
            cancel();
            ctx.fireChannelInactive();
        }

        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            // Requests for another path, and anything else before the upgrade completes, end the connection.
            ReferenceCountUtil.release(message);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                    .setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (!(event instanceof WebSocketServerProtocolHandler.HandshakeComplete complete)) {
                ctx.fireUserEventTriggered(event);
                return;
            }
            cancel();
            InetSocketAddress address;
            try {
                address = settings.addresses().resolve((InetSocketAddress) ctx.channel().remoteAddress(),
                        complete.requestHeaders());
            } catch (IllegalArgumentException invalid) {
                LOGGER.debug("Rejecting WebSocket connection from {}: {}", ctx.channel().remoteAddress(),
                        invalid.getMessage());
                ctx.close();
                return;
            }
            ctx.pipeline().replace(this, FRAME_ADAPTER, new WebSocketFrameAdapter());
            ready.accept(ctx.channel(), address);
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOGGER.debug("Closing WebSocket connection from {} before upgrade", ctx.channel().remoteAddress(), cause);
            ctx.close();
        }

        private void cancel() {
            if (deadline != null) deadline.cancel(false);
        }
    }
}

package dev.moonbridge.clientws.net;

import java.security.NoSuchAlgorithmException;
import java.util.Collections;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.ssl.SslHandler;

/**
 * Installs the WebSocket transport in front of the vanilla handlers. Free of Minecraft classes so it can be tested on
 * its own.
 *
 * <p>
 * Resulting order from the socket inwards: {@code mbws-ssl} (wss only) → {@code mbws-handshake} (removed after the 101)
 * → {@code mbws-ws-decoder} / {@code mbws-ws-encoder} → {@code mbws-frames} → the untouched vanilla handlers
 * ({@code timeout}, {@code splitter}, {@code decoder}, {@code prepender}, {@code encoder}, {@code packet_handler}). The
 * vanilla handler names are not changed, so {@code NetworkManager} can still insert its cipher handlers relative to
 * {@code splitter} and {@code prepender}.
 */
public final class WebSocketPipeline {

    public static final String SSL = "mbws-ssl";
    public static final String HANDSHAKE = "mbws-handshake";
    public static final String FRAME_DECODER = "mbws-ws-decoder";
    public static final String FRAME_ENCODER = "mbws-ws-encoder";
    public static final String FRAMES = "mbws-frames";

    /** Upper bound for a single inbound frame payload; the proxy sends far smaller chunks. */
    public static final int MAX_FRAME_PAYLOAD = 16 * 1024 * 1024;

    private WebSocketPipeline() {}

    public static void install(ChannelPipeline pipeline, WebSocketAddress address) {
        SSLContext tls = null;
        if (address.isSecure()) {
            try {
                tls = SSLContext.getDefault();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("no default TLS context", e);
            }
        }
        install(pipeline, address, tls);
    }

    /** Variant with an explicit TLS context (tests); {@code tls} is ignored for {@code ws://}. */
    public static void install(ChannelPipeline pipeline, WebSocketAddress address, SSLContext tls) {
        // addFirst in reverse so the final order is ssl, handshake, decoder, encoder, frames, vanilla...
        pipeline.addFirst(FRAMES, new WebSocketFrameAdapter());
        pipeline.addFirst(FRAME_ENCODER, new WebSocket13FrameEncoder(true));
        pipeline.addFirst(FRAME_DECODER, new WebSocket13FrameDecoder(false, false, MAX_FRAME_PAYLOAD));
        pipeline.addFirst(HANDSHAKE, new WebSocketHandshakeHandler(address));
        if (address.isSecure()) {
            pipeline.addFirst(SSL, new SslHandler(newClientEngine(tls, address)));
        }
    }

    /** Client engine with SNI = URL host and HTTPS hostname verification against the certificate. */
    public static SSLEngine newClientEngine(SSLContext tls, WebSocketAddress address) {
        SSLEngine engine = tls.createSSLEngine(address.host(), address.port());
        engine.setUseClientMode(true);
        SSLParameters params = engine.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        if (!address.isIpLiteral()) {
            params.setServerNames(Collections.singletonList(new SNIHostName(address.host())));
        }
        engine.setSSLParameters(params);
        return engine;
    }
}

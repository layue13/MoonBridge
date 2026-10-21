package dev.moonbridge.clientws.net;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Locale;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

/**
 * Client side of the RFC 6455 opening handshake.
 *
 * <p>
 * On channel activation (after TLS, if any) it sends the HTTP/1.1 Upgrade request, then reads the response head. Until
 * the {@code 101} response is verified, every outbound write from the handlers behind it (the already-encoded WebSocket
 * frames carrying vanilla's handshake/login packets) is held back; afterwards they are written in order, any bytes the
 * server sent after the response head are handed to the frame decoder, and this handler removes itself.
 *
 * <p>
 * Netty 4.0.10's {@code WebSocketClientHandshaker} is not used: it always puts the port into the Host header, matches
 * the Connection header exactly, and when it swaps {@code HttpClientCodec} for the frame decoder, bytes that arrived
 * together with the 101 response are fired from the removed codec context and skip the new decoder.
 */
public final class WebSocketHandshakeHandler extends ChannelDuplexHandler {

    static final int MAX_RESPONSE_HEAD = 8192;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebSocketAddress address;
    private final String key;
    private final String expectedAccept;
    private final ArrayDeque<Object[]> pendingWrites = new ArrayDeque<Object[]>();
    private ByteBuf responseHead;
    private int scanFrom;
    private boolean requestSent;
    private boolean complete;
    private boolean failed;

    public WebSocketHandshakeHandler(WebSocketAddress address) {
        this.address = address;
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        this.key = Base64.getEncoder()
            .encodeToString(nonce);
        this.expectedAccept = acceptFor(key);
    }

    static String acceptFor(String key) {
        try {
            byte[] sha1 = MessageDigest.getInstance("SHA-1")
                .digest((key + GUID).getBytes(ASCII));
            return Base64.getEncoder()
                .encodeToString(sha1);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    String requestText() {
        return "GET " + address.pathAndQuery()
            + " HTTP/1.1\r\n"
            + "Host: "
            + address.hostHeader()
            + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: "
            + key
            + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "User-Agent: MoonBridgeWs\r\n"
            + "\r\n";
    }

    public boolean isComplete() {
        return complete;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        SslHandler ssl = ctx.pipeline()
            .get(SslHandler.class);
        if (ssl == null) {
            sendRequest(ctx);
        } else {
            ssl.handshakeFuture()
                .addListener(new GenericFutureListener<Future<? super io.netty.channel.Channel>>() {

                    @Override
                    public void operationComplete(Future<? super io.netty.channel.Channel> future) {
                        if (future.isSuccess()) {
                            sendRequest(ctx);
                        } else {
                            fail(
                                ctx,
                                "TLS handshake with " + address.host() + " failed: " + future.cause(),
                                future.cause());
                        }
                    }
                });
        }
        ctx.fireChannelActive();
    }

    private void sendRequest(ChannelHandlerContext ctx) {
        if (requestSent || failed
            || !ctx.channel()
                .isActive()) {
            return;
        }
        requestSent = true;
        ctx.writeAndFlush(Unpooled.copiedBuffer(requestText(), ASCII))
            .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (complete || !(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf in = (ByteBuf) msg;
        if (failed) {
            in.release();
            return;
        }
        if (responseHead == null) {
            responseHead = ctx.alloc()
                .buffer(Math.max(256, in.readableBytes()));
        }
        responseHead.writeBytes(in);
        in.release();

        int end = findHeadEnd(responseHead, scanFrom);
        if (end < 0) {
            if (responseHead.readableBytes() > MAX_RESPONSE_HEAD) {
                fail(ctx, "WebSocket handshake response head exceeds " + MAX_RESPONSE_HEAD + " bytes", null);
            } else {
                scanFrom = Math.max(0, responseHead.writerIndex() - 3);
            }
            return;
        }
        String head = responseHead.toString(responseHead.readerIndex(), end - responseHead.readerIndex(), ASCII);
        responseHead.readerIndex(end + 4);
        String problem = verify(head);
        if (problem != null) {
            fail(ctx, "WebSocket handshake with " + address + " failed: " + problem, null);
            return;
        }
        complete(ctx);
    }

    private static int findHeadEnd(ByteBuf buf, int from) {
        int start = Math.max(buf.readerIndex(), from);
        for (int i = start; i + 3 < buf.writerIndex(); i++) {
            if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n'
                && buf.getByte(i + 2) == '\r'
                && buf.getByte(i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** Returns null if the response head accepts the upgrade, otherwise the reason it does not. */
    String verify(String head) {
        String[] lines = head.split("\r\n");
        String statusLine = lines[0];
        String[] status = statusLine.split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/")) {
            return "malformed HTTP status line '" + statusLine + "'";
        }
        if (!"101".equals(status[1])) {
            return "server answered '" + statusLine
                + "' instead of 101 (wrong path, or the CDN/proxy does not forward WebSocket?)";
        }
        String upgrade = null;
        StringBuilder connection = new StringBuilder();
        String accept = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = lines[i].substring(0, colon)
                .trim()
                .toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1)
                .trim();
            if (name.equals("upgrade")) {
                upgrade = value;
            } else if (name.equals("connection")) {
                connection.append(',')
                    .append(value);
            } else if (name.equals("sec-websocket-accept")) {
                accept = value;
            } else if (name.equals("sec-websocket-extensions") && !value.isEmpty()) {
                return "server selected extension '" + value + "' that was not offered";
            } else if (name.equals("sec-websocket-protocol") && !value.isEmpty()) {
                return "server selected subprotocol '" + value + "' that was not offered";
            }
        }
        if (upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
            return "missing or wrong Upgrade header: " + upgrade;
        }
        boolean hasUpgradeToken = false;
        for (String token : connection.toString()
            .split(",")) {
            if (token.trim()
                .equalsIgnoreCase("upgrade")) {
                hasUpgradeToken = true;
            }
        }
        if (!hasUpgradeToken) {
            return "Connection header does not contain 'Upgrade'";
        }
        if (!expectedAccept.equals(accept)) {
            return "wrong Sec-WebSocket-Accept: " + accept;
        }
        return null;
    }

    private void complete(ChannelHandlerContext ctx) {
        complete = true;
        ByteBuf leftover = responseHead;
        responseHead = null;
        Object[] write;
        boolean wrote = false;
        while ((write = pendingWrites.poll()) != null) {
            ctx.write(write[0], (ChannelPromise) write[1]);
            wrote = true;
        }
        if (wrote) {
            ctx.flush();
        }
        if (leftover.isReadable()) {
            ctx.fireChannelRead(leftover);
        } else {
            leftover.release();
        }
        ctx.pipeline()
            .remove(this);
    }

    private void fail(ChannelHandlerContext ctx, String reason, Throwable cause) {
        if (failed || complete) {
            return;
        }
        failed = true;
        releaseState(new IOException(reason, cause));
        ctx.fireExceptionCaught(new IOException(reason, cause));
        ctx.close();
    }

    private void releaseState(Throwable writeFailure) {
        if (responseHead != null) {
            responseHead.release();
            responseHead = null;
        }
        Object[] write;
        while ((write = pendingWrites.poll()) != null) {
            ReferenceCountUtil.release(write[0]);
            ((ChannelPromise) write[1]).tryFailure(writeFailure);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (complete) {
            ctx.write(msg, promise);
        } else if (failed) {
            ReferenceCountUtil.release(msg);
            promise.tryFailure(new ClosedChannelException());
        } else {
            pendingWrites.add(new Object[] { msg, promise });
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        // Held-back writes are flushed when the handshake completes; our own request is flushed directly.
        if (complete) {
            ctx.flush();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (!complete) {
            releaseState(new ClosedChannelException());
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        if (!complete) {
            releaseState(new ClosedChannelException());
        }
    }
}

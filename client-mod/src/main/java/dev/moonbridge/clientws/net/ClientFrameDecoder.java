package dev.moonbridge.clientws.net;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/**
 * Client-side WebSocket frame decoder for server (unmasked) frames.
 *
 * <p>
 * Binary and continuation payloads are passed on as plain {@link ByteBuf}s: retained slices of the received buffer,
 * emitted as soon as their bytes arrive, so a large frame is never gathered and nothing is copied. Only a frame head
 * split across reads (at most 14 bytes, or a control frame of up to 125 bytes) is copied into a small carry buffer.
 * Ping, pong and close become the usual frame objects; a text frame becomes a {@link TextWebSocketFrame} with an empty
 * payload, after which input is ignored (the adapter closes with 1003).
 *
 * <p>
 * Checks match Netty's {@code WebSocket13FrameDecoder(false, false, 16 MiB)}: masked frames, RSV bits, reserved
 * opcodes, fragmented or oversized control frames, non-minimal lengths, bad continuations, invalid close bodies and
 * frames over {@link WebSocketPipeline#MAX_FRAME_PAYLOAD} are protocol violations, which close the connection and
 * raise a {@link CorruptedFrameException}. Input after a close frame or a violation is discarded.
 *
 * <p>
 * This is not a {@code ByteToMessageDecoder}: in Netty 4.0.10 that class calls {@code discardSomeReadBytes()} on its
 * cumulation before it passes decoded messages on, which moves bytes under slices that were just taken from it.
 */
public final class ClientFrameDecoder extends ChannelInboundHandlerAdapter {

    private static final int MAX_HEAD = 14 + 125;

    private final long maxPayload;
    private ByteBuf carry;
    private long payloadLeft;
    private boolean fragmented;
    private boolean discarding;

    public ClientFrameDecoder() {
        this(WebSocketPipeline.MAX_FRAME_PAYLOAD);
    }

    ClientFrameDecoder(long maxPayload) {
        this.maxPayload = maxPayload;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf in = (ByteBuf) msg;
        try {
            if (carry != null && !completeCarriedHead(ctx, in)) {
                return;
            }
            decode(ctx, in);
        } finally {
            in.release();
        }
    }

    private void decode(ChannelHandlerContext ctx, ByteBuf in) {
        while (in.isReadable()) {
            if (discarding) {
                in.skipBytes(in.readableBytes());
                return;
            }
            if (payloadLeft > 0) {
                int n = (int) Math.min(payloadLeft, in.readableBytes());
                payloadLeft -= n;
                ctx.fireChannelRead(
                    in.readSlice(n)
                        .retain());
                continue;
            }
            int head = headLength(in);
            if (head < 0 || head > in.readableBytes()) {
                carry = ctx.alloc()
                    .buffer(MAX_HEAD);
                carry.writeBytes(in);
                return;
            }
            readHead(ctx, in);
        }
    }

    /** Moves just enough bytes from {@code in} to finish the carried head; true once it has been decoded. */
    private boolean completeCarriedHead(ChannelHandlerContext ctx, ByteBuf in) {
        while (true) {
            int head = headLength(carry);
            int want = (head < 0 ? 2 : head) - carry.readableBytes();
            if (want <= 0) {
                break;
            }
            if (!in.isReadable()) {
                return false;
            }
            carry.writeBytes(in, Math.min(want, in.readableBytes()));
        }
        ByteBuf head = carry;
        carry = null;
        try {
            readHead(ctx, head);
        } finally {
            head.release();
        }
        return true;
    }

    /**
     * Bytes needed for the frame head starting at the reader index, including a control frame's payload; -1 if fewer
     * than two bytes are readable. Masking keys are not counted: a masked frame is rejected first.
     */
    private static int headLength(ByteBuf buf) {
        if (buf.readableBytes() < 2) {
            return -1;
        }
        int b0 = buf.getUnsignedByte(buf.readerIndex());
        int len7 = buf.getUnsignedByte(buf.readerIndex() + 1) & 0x7F;
        if ((b0 & 0x08) != 0) {
            // Control frame: the head carries the whole payload, unless the length is already invalid.
            return len7 <= 125 ? 2 + len7 : 2;
        }
        return len7 < 126 ? 2 : len7 == 126 ? 4 : 10;
    }

    private void readHead(ChannelHandlerContext ctx, ByteBuf buf) {
        int b0 = buf.readUnsignedByte();
        int b1 = buf.readUnsignedByte();
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        if ((b0 & 0x70) != 0) {
            throw violation(ctx, "RSV != 0 and no extension negotiated, RSV:" + ((b0 & 0x70) >> 4));
        }
        if ((b1 & 0x80) != 0) {
            throw violation(ctx, "masked server to client frame");
        }
        int len7 = b1 & 0x7F;
        if (opcode >= 8) {
            if (!fin) {
                throw violation(ctx, "fragmented control frame");
            }
            if (len7 > 125) {
                throw violation(ctx, "control frame with payload length > 125 octets");
            }
            if (opcode > ClientFrameEncoder.OPCODE_PONG) {
                throw violation(ctx, "control frame using reserved opcode " + opcode);
            }
            if (opcode == ClientFrameEncoder.OPCODE_CLOSE) {
                checkCloseBody(ctx, buf, len7);
            }
            ByteBuf payload = len7 == 0 ? Unpooled.EMPTY_BUFFER : buf.readBytes(len7);
            if (opcode == ClientFrameEncoder.OPCODE_CLOSE) {
                discarding = true;
                ctx.fireChannelRead(new CloseWebSocketFrame(true, 0, payload));
            } else if (opcode == ClientFrameEncoder.OPCODE_PING) {
                ctx.fireChannelRead(new PingWebSocketFrame(payload));
            } else {
                ctx.fireChannelRead(new PongWebSocketFrame(payload));
            }
            return;
        }
        if (opcode > ClientFrameEncoder.OPCODE_BINARY) {
            throw violation(ctx, "data frame using reserved opcode " + opcode);
        }
        if (opcode == ClientFrameEncoder.OPCODE_CONTINUATION && !fragmented) {
            throw violation(ctx, "received continuation data frame outside fragmented message");
        }
        if (opcode != ClientFrameEncoder.OPCODE_CONTINUATION && fragmented) {
            throw violation(ctx, "received non-continuation data frame while inside fragmented message");
        }
        long length = len7;
        if (len7 == 126) {
            length = buf.readUnsignedShort();
            if (length < 126) {
                throw violation(ctx, "invalid data frame length (not using minimal length encoding)");
            }
        } else if (len7 == 127) {
            length = buf.readLong();
            if (length < 65536) {
                throw violation(ctx, "invalid data frame length (not using minimal length encoding)");
            }
        }
        if (length > maxPayload) {
            throw violation(ctx, "Max frame length of " + maxPayload + " has been exceeded.");
        }
        if (opcode == ClientFrameEncoder.OPCODE_TEXT) {
            discarding = true;
            ctx.fireChannelRead(new TextWebSocketFrame(Unpooled.EMPTY_BUFFER));
            return;
        }
        fragmented = !fin;
        payloadLeft = length;
    }

    private void checkCloseBody(ChannelHandlerContext ctx, ByteBuf buf, int length) {
        if (length == 0) {
            return;
        }
        if (length == 1) {
            throw violation(ctx, "Invalid close frame body");
        }
        int status = buf.getUnsignedShort(buf.readerIndex());
        if (status <= 999 || status >= 1004 && status <= 1006 || status >= 1012 && status <= 2999) {
            throw violation(ctx, "Invalid close frame getStatus code: " + status);
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(buf.nioBuffer(buf.readerIndex() + 2, length - 2));
        } catch (CharacterCodingException e) {
            throw violation(ctx, "Invalid close frame reason text. Invalid UTF-8 bytes");
        }
    }

    private CorruptedFrameException violation(ChannelHandlerContext ctx, String reason) {
        discarding = true;
        payloadLeft = 0;
        if (ctx.channel()
            .isActive()) {
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER)
                .addListener(ChannelFutureListener.CLOSE);
        }
        return new CorruptedFrameException(reason);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        releaseCarry();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseCarry();
    }

    private void releaseCarry() {
        if (carry != null) {
            carry.release();
            carry = null;
        }
    }
}

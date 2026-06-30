package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCodecException;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.OptionalInt;

final class MinecraftCompressionNegotiationDetector {
    static final int CLIENTBOUND_LOGIN_SET_COMPRESSION_PACKET_ID = 0x03;

    private final int maxFrameBytes;
    private ByteBuf pending = Unpooled.buffer();
    private boolean complete;

    MinecraftCompressionNegotiationDetector(int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    OptionalInt observe(ByteBuf input) {
        if (complete || !input.isReadable()) {
            return OptionalInt.empty();
        }
        if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
            complete = true;
            discardPending();
            throw new MinecraftCodecException("compression negotiation probe exceeded maximum frame size");
        }
        pending.writeBytes(input, input.readerIndex(), input.readableBytes());
        while (pending.isReadable()) {
            var frameLength = MinecraftVarInts.probe(pending);
            if (!frameLength.complete()) {
                return OptionalInt.empty();
            }
            if (frameLength.value() < 0 || frameLength.value() > maxFrameBytes) {
                complete = true;
                discardPending();
                throw new MinecraftCodecException("compression negotiation frame exceeds maximum size");
            }
            var totalBytes = frameLength.bytes() + frameLength.value();
            if (pending.readableBytes() < totalBytes) {
                return OptionalInt.empty();
            }
            var frame = pending.readRetainedSlice(totalBytes);
            try {
                var payload = frame.slice(frameLength.bytes(), frameLength.value());
                var packetId = MinecraftVarInts.read(payload);
                if (packetId == CLIENTBOUND_LOGIN_SET_COMPRESSION_PACKET_ID) {
                    var threshold = MinecraftVarInts.read(payload);
                    complete = true;
                    discardPending();
                    return OptionalInt.of(threshold);
                }
            } finally {
                frame.release();
            }
            pending.discardReadBytes();
        }
        return OptionalInt.empty();
    }

    boolean complete() {
        return complete;
    }

    void close() {
        complete = true;
        discardPending();
    }

    private void discardPending() {
        if (pending.refCnt() > 0) {
            pending.release();
        }
        pending = Unpooled.EMPTY_BUFFER;
    }
}

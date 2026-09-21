package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCodecException;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.OptionalInt;

final class MinecraftCompressionNegotiationDetector {
    private final int maxFrameBytes;
    private final MinecraftProtocolProfile profile;
    private ByteBuf pending = Unpooled.buffer();
    private boolean complete;

    MinecraftCompressionNegotiationDetector(int maxFrameBytes) {
        this(maxFrameBytes, MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1));
    }

    MinecraftCompressionNegotiationDetector(int maxFrameBytes, int protocolVersion) {
        this(maxFrameBytes, MinecraftProtocolProfile.forVersion(protocolVersion));
    }

    MinecraftCompressionNegotiationDetector(int maxFrameBytes, MinecraftProtocolProfile profile) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.profile = profile == null
                ? MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1)
                : profile;
        if (!this.profile.compressionNegotiationSupported()) {
            pending.release();
            complete = true;
            pending = Unpooled.EMPTY_BUFFER;
        }
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
                if (packetId == profile.clientboundLoginSuccessPacketId()) {
                    complete = true;
                    discardPending();
                    return OptionalInt.empty();
                }
                if (profile.clientboundLoginSetCompressionPacketId().isPresent()
                        && packetId == profile.clientboundLoginSetCompressionPacketId().getAsInt()) {
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

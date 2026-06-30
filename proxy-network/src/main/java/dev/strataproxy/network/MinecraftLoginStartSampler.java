package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCodecException;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Optional;

final class MinecraftLoginStartSampler {
    private final int maxFrameBytes;
    private ByteBuf pending = Unpooled.buffer();
    private boolean closed;
    private boolean found;

    MinecraftLoginStartSampler(int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    Optional<String> observe(ByteBuf input) {
        if (closed || found || !input.isReadable()) {
            return Optional.empty();
        }
        if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
            close();
            throw new MinecraftCodecException("login start sampling exceeded maximum frame size");
        }
        pending.writeBytes(input, input.readerIndex(), input.readableBytes());
        var frameLength = MinecraftVarInts.probe(pending);
        if (!frameLength.complete()) {
            return Optional.empty();
        }
        if (frameLength.value() < 0 || frameLength.value() > maxFrameBytes) {
            close();
            throw new MinecraftCodecException("login start frame exceeds maximum size");
        }
        var totalBytes = frameLength.bytes() + frameLength.value();
        if (pending.readableBytes() < totalBytes) {
            return Optional.empty();
        }
        var frame = pending.readRetainedSlice(totalBytes);
        try {
            var body = frame.slice(frameLength.bytes(), frameLength.value()).retainedDuplicate();
            try {
                var packetId = MinecraftVarInts.read(body);
                if (packetId != 0) {
                    close();
                    return Optional.empty();
                }
                var username = MinecraftProtocolCodec.readString(body, 16);
                found = true;
                close();
                return Optional.of(username);
            } finally {
                body.release();
            }
        } finally {
            frame.release();
        }
    }

    void close() {
        closed = true;
        if (pending.refCnt() > 0) {
            pending.release();
        }
        pending = Unpooled.EMPTY_BUFFER;
    }
}

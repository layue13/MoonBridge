package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCodecException;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

final class MinecraftPacketTrafficSampler {
    private final int maxFrameBytes;
    private ByteBuf pending = Unpooled.buffer();
    private boolean closed;

    MinecraftPacketTrafficSampler(int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    List<PacketTrafficSample> observe(ByteBuf input) {
        if (closed || !input.isReadable()) {
            return List.of();
        }
        if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
            close();
            throw new MinecraftCodecException("packet traffic sampling exceeded maximum frame size");
        }
        pending.writeBytes(input, input.readerIndex(), input.readableBytes());
        var samples = new ArrayList<PacketTrafficSample>();
        while (pending.isReadable()) {
            var frameLength = MinecraftVarInts.probe(pending);
            if (!frameLength.complete()) {
                break;
            }
            if (frameLength.value() < 0 || frameLength.value() > maxFrameBytes) {
                close();
                throw new MinecraftCodecException("packet traffic frame exceeds maximum size");
            }
            var totalBytes = frameLength.bytes() + frameLength.value();
            if (pending.readableBytes() < totalBytes) {
                break;
            }
            var frame = pending.readRetainedSlice(totalBytes);
            try {
                var body = frame.slice(frameLength.bytes(), frameLength.value());
                var bodyProbe = body.retainedDuplicate();
                try {
                    var packetId = MinecraftVarInts.read(bodyProbe);
                    samples.add(new PacketTrafficSample(packetId, totalBytes, frameLength.value()));
                } finally {
                    bodyProbe.release();
                }
            } finally {
                frame.release();
            }
            pending.discardReadBytes();
        }
        return samples;
    }

    void close() {
        closed = true;
        if (pending.refCnt() > 0) {
            pending.release();
        }
        pending = Unpooled.EMPTY_BUFFER;
    }

    record PacketTrafficSample(int packetId, int frameBytes, int payloadBytes) {
    }
}

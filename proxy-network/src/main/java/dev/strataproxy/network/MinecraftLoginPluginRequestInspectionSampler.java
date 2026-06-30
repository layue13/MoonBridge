package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCodecException;
import dev.strataproxy.codec.minecraft.MinecraftCustomPayloadClassifier;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

final class MinecraftLoginPluginRequestInspectionSampler {
    static final int CLIENTBOUND_LOGIN_PLUGIN_REQUEST_PACKET_ID = 0x04;

    private final int maxFrameBytes;
    private final int largePayloadBytes;
    private ByteBuf pending = Unpooled.buffer();
    private boolean closed;

    MinecraftLoginPluginRequestInspectionSampler(int maxFrameBytes, int largePayloadBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        if (largePayloadBytes < 0) {
            throw new IllegalArgumentException("largePayloadBytes must be non-negative");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.largePayloadBytes = largePayloadBytes;
    }

    List<MinecraftCustomPayloadClassifier.CustomPayloadClassification> observe(ByteBuf input) {
        if (closed || !input.isReadable()) {
            return List.of();
        }
        if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
            close();
            throw new MinecraftCodecException("login plugin request inspection exceeded maximum frame size");
        }
        pending.writeBytes(input, input.readerIndex(), input.readableBytes());
        var results = new ArrayList<MinecraftCustomPayloadClassifier.CustomPayloadClassification>();
        while (pending.isReadable()) {
            var frameLength = MinecraftVarInts.probe(pending);
            if (!frameLength.complete()) {
                break;
            }
            if (frameLength.value() < 0 || frameLength.value() > maxFrameBytes) {
                close();
                throw new MinecraftCodecException("login plugin request inspection frame exceeds maximum size");
            }
            var totalBytes = frameLength.bytes() + frameLength.value();
            if (pending.readableBytes() < totalBytes) {
                break;
            }
            var frame = pending.readRetainedSlice(totalBytes);
            try {
                var body = frame.slice(frameLength.bytes(), frameLength.value());
                if (packetId(body) == CLIENTBOUND_LOGIN_PLUGIN_REQUEST_PACKET_ID) {
                    results.add(MinecraftCustomPayloadClassifier.classifyLoginPluginRequest(body, largePayloadBytes));
                }
            } finally {
                frame.release();
            }
            pending.discardReadBytes();
        }
        return results;
    }

    private static int packetId(ByteBuf body) {
        var view = body.retainedDuplicate();
        try {
            return MinecraftVarInts.read(view);
        } finally {
            view.release();
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

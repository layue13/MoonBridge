package dev.strataproxy.core.protocol;

import io.netty.buffer.ByteBuf;
import java.util.Optional;

/** A recognized legacy Forge channel and its opaque, retained message bytes. */
public record ForgeMarker(String channel, ByteBuf payload) implements AutoCloseable {
    public ForgeMarker {
        if (!isForgeChannel(channel)) throw new IllegalArgumentException("unsupported Forge channel");
    }

    /** Inspects a protocol 5 custom-payload packet. The returned payload must be released by the caller. */
    public static Optional<ForgeMarker> detect(ByteBuf packetBody, ProtocolProfile profile) {
        ByteBuf input = packetBody.duplicate();
        if (ProtocolVarInt.read(input) != 0xFA) return Optional.empty();
        String channel = ProtocolStrings.read(input, 20);
        if (!isForgeChannel(channel)) return Optional.empty();
        return Optional.of(new ForgeMarker(channel, input.readRetainedSlice(input.readableBytes())));
    }

    @Override public void close() { payload.release(); }

    private static boolean isForgeChannel(String channel) {
        return "FML|HS".equals(channel) || "FML|MP".equals(channel) || "FML".equals(channel);
    }
}

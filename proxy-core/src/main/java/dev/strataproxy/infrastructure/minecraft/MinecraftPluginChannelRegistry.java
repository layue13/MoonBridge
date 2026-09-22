package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.infrastructure.minecraft.codec.MinecraftCompressionCodec;
import dev.strataproxy.infrastructure.minecraft.codec.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class MinecraftPluginChannelRegistry implements AutoCloseable {
    private static final int MAX_CHANNELS = 1024;
    private static final int MAX_CHANNEL_BYTES = 128;

    private final int maxFrameBytes;
    private final LinkedHashSet<String> channels = new LinkedHashSet<>();
    private final MinecraftCompressionCodec compressionCodec = new MinecraftCompressionCodec();
    private ByteBuf pending = Unpooled.buffer();
    private Boolean pendingCompressed;
    private boolean closed;

    MinecraftPluginChannelRegistry(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    synchronized void observeServerbound(ByteBuf input, MinecraftProtocolProfile profile) {
        if (closed
                || input == null
                || !input.isReadable()
                || profile == null
                || profile.serverboundCustomPayloadPacketId().isEmpty()) {
            return;
        }
        if (pending.isReadable()) {
            appendPending(input, false);
            observeFrames(pending, profile);
            pending.discardReadBytes();
            return;
        }
        var frames = input.slice();
        observeFrames(frames, profile);
        if (frames.isReadable()) {
            appendPending(frames, false);
        }
    }

    private void observeFrames(ByteBuf frames, MinecraftProtocolProfile profile) {
        while (frames.isReadable()) {
            var probe = MinecraftProtocolCodec.probeFrame(frames, maxFrameBytes);
            if (!probe.complete()) {
                break;
            }
            var frame = frames.readSlice(probe.totalBytes());
            observeFrame(frame, profile);
        }
    }

    synchronized void observeServerboundCompressed(
            ByteBufAllocator allocator,
            ByteBuf input,
            int threshold,
            MinecraftProtocolProfile profile) {
        if (closed
                || input == null
                || !input.isReadable()
                || allocator == null
                || profile == null
                || profile.serverboundCustomPayloadPacketId().isEmpty()) {
            return;
        }
        if (pending.isReadable()) {
            appendPending(input, true);
            observeCompressedFrames(allocator, pending, threshold, profile);
            pending.discardReadBytes();
            return;
        }
        var frames = input.slice();
        observeCompressedFrames(allocator, frames, threshold, profile);
        if (frames.isReadable()) {
            appendPending(frames, true);
        }
    }

    private void observeCompressedFrames(
            ByteBufAllocator allocator, ByteBuf frames, int threshold, MinecraftProtocolProfile profile) {
        while (frames.isReadable()) {
            var probe = MinecraftProtocolCodec.probeFrame(frames, maxFrameBytes);
            if (!probe.complete()) {
                break;
            }
            var frame = frames.readSlice(probe.totalBytes());
            var packet = compressionCodec.decodeFrame(allocator, frame, threshold, maxFrameBytes);
            try {
                observePacket(packet, profile);
            } finally {
                packet.release();
            }
        }
    }

    synchronized ByteBuf registrationFrame(ByteBufAllocator allocator, MinecraftProtocolProfile profile) {
        if (closed
                || channels.isEmpty()
                || profile == null
                || profile.serverboundCustomPayloadPacketId().isEmpty()) {
            return null;
        }
        var payloadBytes = joinedChannelsSnapshot().getBytes(StandardCharsets.UTF_8);
        if (payloadBytes.length == 0 || payloadBytes.length > maxFrameBytes) {
            return null;
        }
        var packet = allocator.buffer();
        try {
            MinecraftVarInts.write(packet, profile.serverboundCustomPayloadPacketId().getAsInt());
            writeString(packet, "REGISTER");
            MinecraftCustomPayloadBodyCodec.writeLength(
                    packet,
                    profile.serverboundCustomPayloadLengthFormat(),
                    payloadBytes.length);
            packet.writeBytes(payloadBytes);
            var frame = allocator.buffer(MinecraftVarInts.encodedSize(packet.readableBytes()) + packet.readableBytes());
            MinecraftVarInts.write(frame, packet.readableBytes());
            frame.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
            return frame;
        } finally {
            packet.release();
        }
    }

    synchronized Set<String> channels() {
        return Set.copyOf(channels);
    }

    @Override
    public synchronized void close() {
        closed = true;
        channels.clear();
        if (pending != null && pending.refCnt() > 0) {
            pending.release();
        }
        pending = Unpooled.EMPTY_BUFFER;
        compressionCodec.close();
    }

    private void observeFrame(ByteBuf frame, MinecraftProtocolProfile profile) {
        var packet = frame.retainedDuplicate();
        try {
            MinecraftProtocolCodec.readVarInt(packet);
            observePacket(packet, profile);
        } finally {
            packet.release();
        }
    }

    private void observePacket(ByteBuf packet, MinecraftProtocolProfile profile) {
        var packetId = MinecraftProtocolCodec.readVarInt(packet);
        if (packetId != profile.serverboundCustomPayloadPacketId().getAsInt()) {
            return;
        }
        var channel = MinecraftProtocolCodec.readString(packet, MAX_CHANNEL_BYTES);
        if (!"REGISTER".equals(channel) && !"UNREGISTER".equals(channel)) {
            return;
        }
        var payload = MinecraftCustomPayloadBodyCodec.readBody(
                packet,
                profile.serverboundCustomPayloadLengthFormat());
        var names = splitChannels(payload);
        if ("REGISTER".equals(channel)) {
            for (var name : names) {
                if (channels.size() >= MAX_CHANNELS && !channels.contains(name)) {
                    break;
                }
                channels.add(name);
            }
        } else {
            channels.removeAll(names);
        }
    }

    private void appendPending(ByteBuf input, boolean compressed) {
        if (pendingCompressed == null) {
            pendingCompressed = compressed;
        } else if (pendingCompressed != compressed) {
            pending.clear();
            pendingCompressed = compressed;
        }
        if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
            pending.clear();
            throw new IllegalStateException("plugin channel registry pending frame exceeded maximum size");
        }
        pending.writeBytes(input, input.readerIndex(), input.readableBytes());
    }

    private String joinedChannelsSnapshot() {
        return String.join("\0", channels);
    }

    private static List<String> splitChannels(ByteBuf payload) {
        if (!payload.isReadable()) {
            return List.of();
        }
        var bytes = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), bytes);
        var names = new ArrayList<String>();
        var start = 0;
        for (var index = 0; index <= bytes.length; index++) {
            if (index == bytes.length || bytes[index] == 0) {
                if (index > start && index - start <= MAX_CHANNEL_BYTES) {
                    var name = new String(bytes, start, index - start, StandardCharsets.UTF_8);
                    if (!name.isBlank()) {
                        names.add(name);
                    }
                }
                start = index + 1;
            }
        }
        return names;
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }

}

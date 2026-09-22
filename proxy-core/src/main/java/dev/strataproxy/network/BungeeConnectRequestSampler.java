package dev.strataproxy.network;

import dev.strataproxy.network.MinecraftCompressionCodec;
import dev.strataproxy.network.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class BungeeConnectRequestSampler implements AutoCloseable {
    private static final int MAX_CHANNEL_BYTES = 128;
    private static final int MAX_BUNGEE_STRING_BYTES = 32767;

    private final int maxFrameBytes;
    private final MinecraftProtocolProfile profile;
    private final MinecraftCompressionCodec codec = new MinecraftCompressionCodec();
    private ByteBuf pending = Unpooled.buffer();
    private boolean closed;

    BungeeConnectRequestSampler(int maxFrameBytes) {
        this(maxFrameBytes, MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1));
    }

    BungeeConnectRequestSampler(int maxFrameBytes, MinecraftProtocolProfile profile) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.profile = profile == null
                ? MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1)
                : profile;
    }

    List<ConnectRequest> observeUncompressed(ByteBuf input) {
        return observe(input, false, null, -1);
    }

    List<ConnectRequest> observeCompressed(ByteBufAllocator allocator, ByteBuf input, int threshold) {
        return observe(input, true, allocator, threshold);
    }

    private List<ConnectRequest> observe(ByteBuf input, boolean compressed, ByteBufAllocator allocator, int threshold) {
        if (closed || !input.isReadable()) {
            return List.of();
        }
        if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
            close();
            return List.of();
        }
        pending.writeBytes(input, input.readerIndex(), input.readableBytes());
        var requests = new ArrayList<ConnectRequest>();
        while (pending.isReadable()) {
            var frameLength = MinecraftVarInts.probe(pending);
            if (!frameLength.complete()) {
                break;
            }
            if (frameLength.value() < 0 || frameLength.value() > maxFrameBytes) {
                close();
                break;
            }
            var totalBytes = frameLength.bytes() + frameLength.value();
            if (pending.readableBytes() < totalBytes) {
                break;
            }
            var frame = pending.readRetainedSlice(totalBytes);
            try {
                parseFrame(allocator, frame, compressed, threshold).ifPresent(requests::add);
            } finally {
                frame.release();
            }
            pending.discardReadBytes();
        }
        return requests;
    }

    private Optional<ConnectRequest> parseFrame(ByteBufAllocator allocator, ByteBuf frame, boolean compressed, int threshold) {
        ByteBuf encoded = null;
        ByteBuf decoded = null;
        try {
            if (compressed) {
                encoded = frame.retainedDuplicate();
                decoded = codec.decodeFrame(allocator, encoded, threshold, maxFrameBytes);
            } else {
                decoded = decodeUncompressedFrame(frame);
            }
            return parsePacket(decoded);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        } finally {
            if (encoded != null) {
                encoded.release();
            }
            if (decoded != null) {
                decoded.release();
            }
        }
    }

    private static ByteBuf decodeUncompressedFrame(ByteBuf frame) {
        var view = frame.retainedDuplicate();
        try {
            var length = MinecraftVarInts.read(view);
            return view.readRetainedSlice(length);
        } finally {
            view.release();
        }
    }

    private Optional<ConnectRequest> parsePacket(ByteBuf packet) {
        var view = packet.retainedDuplicate();
        try {
            var packetId = MinecraftVarInts.read(view);
            if (!profile.serverboundBungeeCustomPayloadPacketIds().contains(packetId)) {
                return Optional.empty();
            }
            var channel = readMinecraftString(view, MAX_CHANNEL_BYTES);
            if (!isBungeeChannel(channel)) {
                return Optional.empty();
            }
            var payload = MinecraftCustomPayloadBodyCodec.readBody(
                    view,
                    profile.serverboundCustomPayloadLengthFormat());
            var subchannel = readUnsignedShortString(payload);
            if (!"connect".equalsIgnoreCase(subchannel)) {
                return Optional.empty();
            }
            var targetServer = readUnsignedShortString(payload);
            if (targetServer.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ConnectRequest(packetId, channel, targetServer.trim()));
        } finally {
            view.release();
        }
    }

    private static boolean isBungeeChannel(String channel) {
        var normalized = channel.toLowerCase(Locale.ROOT);
        return normalized.equals("bungeecord") || normalized.equals("bungeecord:main");
    }

    private static String readMinecraftString(ByteBuf input, int maxBytes) {
        var length = MinecraftVarInts.read(input);
        if (length < 0 || length > maxBytes || input.readableBytes() < length) {
            throw new IllegalArgumentException("invalid minecraft string");
        }
        var value = input.toString(input.readerIndex(), length, StandardCharsets.UTF_8);
        input.skipBytes(length);
        return value;
    }

    private static String readUnsignedShortString(ByteBuf input) {
        if (input.readableBytes() < Short.BYTES) {
            throw new IllegalArgumentException("truncated bungee string");
        }
        var length = input.readUnsignedShort();
        if (length > MAX_BUNGEE_STRING_BYTES || input.readableBytes() < length) {
            throw new IllegalArgumentException("invalid bungee string");
        }
        var value = input.toString(input.readerIndex(), length, StandardCharsets.UTF_8);
        input.skipBytes(length);
        return value;
    }

    @Override
    /** Provides close. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (pending.refCnt() > 0) {
            pending.release();
        }
        pending = Unpooled.EMPTY_BUFFER;
        codec.close();
    }

    record ConnectRequest(int packetId, String channel, String targetServer) {
    }
}

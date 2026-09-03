package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCompressionCodec;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MinecraftForgeHandshakeTracker implements AutoCloseable {
    private static final int MAX_CHANNEL_BYTES = 128;
    private static final int MAX_MULTIPART_CHANNEL_BYTES = 20;
    private static final int MAX_MULTIPART_PARTS = 64;
    private static final int MAX_MULTIPART_BYTES = 52_424_750;
    private static final int MAX_MOD_LIST_ENTRIES_TO_SCAN = 4096;

    private final int maxFrameBytes;
    private final MinecraftProtocolProfile profile;
    private final MinecraftCompressionCodec compressionCodec = new MinecraftCompressionCodec();
    private boolean resetRequiredOnNextForgeServer;
    private ByteBuf serverboundPending = Unpooled.buffer();
    private ByteBuf clientboundPending = Unpooled.buffer();
    private ByteBuf serverboundCompressedPending = Unpooled.buffer();
    private ByteBuf clientboundCompressedPending = Unpooled.buffer();
    private MultipartState clientboundMultipart;
    private Map<String, String> clientMods = Map.of();
    private Map<String, String> serverMods = Map.of();
    private int clientboundRegistryPackets;
    private long clientboundRegistryBytes;
    private ClientPhase clientPhase = ClientPhase.NOT_STARTED;
    private BackendPhase backendPhase = BackendPhase.NOT_STARTED;
    private Stage stage = Stage.IDLE;
    private boolean closed;

    MinecraftForgeHandshakeTracker(int maxFrameBytes, MinecraftProtocolProfile profile) {
        this(maxFrameBytes, profile, false);
    }

    MinecraftForgeHandshakeTracker(int maxFrameBytes, MinecraftProtocolProfile profile, boolean resetRequiredOnNextForgeServer) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.profile = profile == null
                ? MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1)
                : profile;
        this.resetRequiredOnNextForgeServer = resetRequiredOnNextForgeServer;
    }

    List<Event> observe(Direction direction, ByteBuf input) {
        if (closed || !profile.legacyForgeHandshakeSupported() || input == null || !input.isReadable()) {
            return List.of();
        }
        var pending = pending(direction);
        if (pending.isReadable()) {
            if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
                close();
                return List.of(new Event(direction, "pending_overflow", "", -1, 0, stage, false));
            }
            pending.writeBytes(input, input.readerIndex(), input.readableBytes());
            var events = observeFrames(direction, pending);
            if (!closed) {
                pending.discardReadBytes();
            }
            return events;
        }
        var frames = input.slice();
        var events = observeFrames(direction, frames);
        appendPartialFrame(direction, pending, frames, events);
        return events;
    }

    private List<Event> observeFrames(Direction direction, ByteBuf frames) {
        var events = new ArrayList<Event>();
        while (frames.isReadable()) {
            var frameLength = MinecraftVarInts.probe(frames);
            if (!frameLength.complete()) {
                break;
            }
            if (frameLength.value() < 0 || frameLength.value() > maxFrameBytes) {
                close();
                events.add(new Event(direction, "frame_oversized", "", -1, 0, stage, false));
                break;
            }
            var totalBytes = frameLength.bytes() + frameLength.value();
            if (frames.readableBytes() < totalBytes) {
                break;
            }
            var frame = frames.readSlice(totalBytes);
            parseFrame(direction, frame, frameLength.bytes(), frameLength.value()).forEach(events::add);
        }
        return events;
    }

    List<Event> observeCompressed(Direction direction, ByteBufAllocator allocator, ByteBuf input, int threshold) {
        if (closed
                || !profile.legacyForgeHandshakeSupported()
                || input == null
                || !input.isReadable()
                || allocator == null) {
            return List.of();
        }
        var pending = compressedPending(direction);
        if (pending.isReadable()) {
            if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
                close();
                return List.of(new Event(direction, "pending_overflow", "", -1, 0, stage, false));
            }
            pending.writeBytes(input, input.readerIndex(), input.readableBytes());
            var events = observeCompressedFrames(direction, allocator, pending, threshold);
            if (!closed) {
                pending.discardReadBytes();
            }
            return events;
        }
        var frames = input.slice();
        var events = observeCompressedFrames(direction, allocator, frames, threshold);
        appendPartialFrame(direction, pending, frames, events);
        return events;
    }

    private List<Event> observeCompressedFrames(
            Direction direction, ByteBufAllocator allocator, ByteBuf frames, int threshold) {
        var events = new ArrayList<Event>();
        while (frames.isReadable()) {
            var frameLength = MinecraftVarInts.probe(frames);
            if (!frameLength.complete()) {
                break;
            }
            if (frameLength.value() < 0 || frameLength.value() > maxFrameBytes) {
                close();
                events.add(new Event(direction, "frame_oversized", "", -1, 0, stage, false));
                break;
            }
            var totalBytes = frameLength.bytes() + frameLength.value();
            if (frames.readableBytes() < totalBytes) {
                break;
            }
            var frame = frames.readSlice(totalBytes);
            try {
                var packet = compressionCodec.decodeFrame(allocator, frame, threshold, maxFrameBytes);
                try {
                    parsePacket(direction, packet, packet.readableBytes()).forEach(events::add);
                } finally {
                    packet.release();
                }
            } catch (RuntimeException exception) {
                events.add(new Event(direction, "malformed", "", -1, frame.readableBytes(), stage, false));
            }
        }
        return events;
    }

    private void appendPartialFrame(Direction direction, ByteBuf pending, ByteBuf frames, List<Event> events) {
        if (closed || !frames.isReadable()) {
            return;
        }
        if (frames.readableBytes() > maxFrameBytes + 5) {
            close();
            events.add(new Event(direction, "pending_overflow", "", -1, 0, stage, false));
            return;
        }
        pending.writeBytes(frames, frames.readerIndex(), frames.readableBytes());
    }

    Stage stage() {
        return stage;
    }

    boolean complete() {
        return clientPhase == ClientPhase.COMPLETE && backendPhase == BackendPhase.COMPLETE;
    }

    boolean backendSwitchBlocked() {
        if (!profile.legacyForgeHandshakeSupported()) {
            return false;
        }
        if (stage == Stage.IDLE
                && clientPhase == ClientPhase.NOT_STARTED
                && backendPhase == BackendPhase.NOT_STARTED) {
            return false;
        }
        if (backendPhase == BackendPhase.NOT_STARTED
                && stage == Stage.CHANNELS_REGISTERED
                && clientPhase == ClientPhase.CHANNELS_REGISTERED) {
            return false;
        }
        if (stage == Stage.RESET
                && clientPhase == ClientPhase.RESET
                && backendPhase == BackendPhase.RESET) {
            return false;
        }
        return !complete();
    }

    boolean consumeResetRequiredOnNextForgeServer() {
        if (!resetRequiredOnNextForgeServer) {
            return false;
        }
        resetRequiredOnNextForgeServer = false;
        return true;
    }

    void resetHandshakeFromProxy() {
        if (closed || !profile.legacyForgeHandshakeSupported()) {
            return;
        }
        resetHandshakeState(false);
        clearPending(clientboundPending);
        stage = Stage.RESET;
        clientPhase = ClientPhase.RESET;
        backendPhase = BackendPhase.RESET;
    }

    private ByteBuf pending(Direction direction) {
        return direction == Direction.SERVERBOUND ? serverboundPending : clientboundPending;
    }

    private ByteBuf compressedPending(Direction direction) {
        return direction == Direction.SERVERBOUND ? serverboundCompressedPending : clientboundCompressedPending;
    }

    private List<Event> parseFrame(Direction direction, ByteBuf frame, int lengthBytes, int payloadBytes) {
        var packet = frame.retainedDuplicate();
        try {
            packet.skipBytes(lengthBytes);
            return parsePacket(direction, packet, payloadBytes);
        } catch (RuntimeException exception) {
            return List.of(new Event(direction, "malformed", "", -1, payloadBytes, stage, false));
        } finally {
            packet.release();
        }
    }

    private List<Event> parsePacket(Direction direction, ByteBuf packet, int packetBytes) {
        try {
            var packetId = MinecraftProtocolCodec.readVarInt(packet);
            if (!customPayloadPacket(direction, packetId)) {
                return List.of();
            }
            var channel = readString(packet, MAX_CHANNEL_BYTES);
            var payload = customPayloadBody(direction, packet);
            if (!forgeChannel(channel)) {
                return List.of();
            }
            return parseForgePayload(direction, channel, payload, packetBytes);
        } catch (RuntimeException exception) {
            return List.of(new Event(direction, "malformed", "", -1, packetBytes, stage, false));
        }
    }

    private boolean customPayloadPacket(Direction direction, int packetId) {
        if (direction == Direction.SERVERBOUND) {
            return profile.serverboundCustomPayloadPacketId().isPresent()
                    && packetId == profile.serverboundCustomPayloadPacketId().getAsInt();
        }
        return profile.clientboundCustomPayloadPacketId().isPresent()
                && packetId == profile.clientboundCustomPayloadPacketId().getAsInt();
    }

    private ByteBuf customPayloadBody(Direction direction, ByteBuf packet) {
        var format = direction == Direction.SERVERBOUND
                ? profile.serverboundCustomPayloadLengthFormat()
                : profile.clientboundCustomPayloadLengthFormat();
        return MinecraftCustomPayloadBodyCodec.readBody(packet, format);
    }

    private List<Event> parseForgePayload(Direction direction, String channel, ByteBuf payload, int packetBytes) {
        if ("REGISTER".equals(channel)) {
            var registered = registeredChannels(payload);
            var matched = registered.contains("FML|HS") || registered.contains("FML|MP") || registered.contains("FORGE");
            if (matched) {
                if (direction == Direction.SERVERBOUND) {
                    advanceClientPhase(ClientPhase.CHANNELS_REGISTERED);
                } else {
                    advanceBackendPhase(BackendPhase.CHANNELS_REGISTERED);
                }
                advance(Stage.CHANNELS_REGISTERED);
            }
            return List.of(new Event(direction, matched ? "register" : "register_other", channel, -1, payload.readableBytes(), stage, true));
        }
        if ("FML|MP".equals(channel)) {
            return parseMultipart(direction, payload);
        }
        if (!"FML|HS".equals(channel)) {
            return List.of(new Event(direction, channel.toLowerCase(Locale.ROOT), channel, -1, payload.readableBytes(), stage, true));
        }
        return List.of(parseHandshakePayload(direction, channel, payload, packetBytes));
    }

    private List<Event> parseMultipart(Direction direction, ByteBuf payload) {
        if (direction != Direction.CLIENTBOUND) {
            return List.of(new Event(direction, "multipart_unexpected_direction", "FML|MP", -1, payload.readableBytes(), stage, false));
        }
        if (clientboundMultipart == null) {
            var readable = payload.readableBytes();
            var wrappedChannel = readString(payload, MAX_MULTIPART_CHANNEL_BYTES);
            if (payload.readableBytes() < 5) {
                return List.of(new Event(direction, "multipart_malformed", "FML|MP", -1, readable, stage, false));
            }
            var parts = payload.readUnsignedByte();
            var totalLength = payload.readInt();
            var expected = parts > 0
                    && parts <= MAX_MULTIPART_PARTS
                    && totalLength >= 0
                    && totalLength <= MAX_MULTIPART_BYTES
                    && forgeChannel(wrappedChannel);
            if (expected) {
                clientboundMultipart = new MultipartState(wrappedChannel, parts, totalLength);
            }
            return List.of(new Event(direction, expected ? "multipart_start" : "multipart_start_invalid",
                    wrappedChannel, -1, totalLength, stage, expected));
        }

        if (!payload.isReadable()) {
            clearMultipart();
            return List.of(new Event(direction, "multipart_empty_part", "FML|MP", -1, 0, stage, false));
        }
        var multipart = clientboundMultipart;
        var part = payload.readUnsignedByte();
        var dataBytes = payload.readableBytes();
        var expected = part == multipart.nextPart
                && multipart.receivedBytes + dataBytes <= multipart.totalLength;
        var events = new ArrayList<Event>();
        events.add(new Event(direction, expected ? "multipart_part" : "multipart_part_unexpected",
                multipart.wrappedChannel, -1, dataBytes, stage, expected));

        if (expected && part == 0 && "FML|HS".equals(multipart.wrappedChannel) && payload.isReadable()) {
            var discriminator = payload.getUnsignedByte(payload.readerIndex());
            if (discriminator == 3 || discriminator == 255) {
                var handshakePayload = payload.retainedDuplicate();
                try {
                    var handshake = parseHandshakePayload(direction, multipart.wrappedChannel, handshakePayload, dataBytes);
                    if (discriminator == 3) {
                        clientboundRegistryBytes += Math.max(0, multipart.totalLength - dataBytes);
                    }
                    events.add(new Event(direction, handshake.type() + "_multipart", handshake.channel(),
                            handshake.discriminator(), multipart.totalLength, handshake.stage(), handshake.expected()));
                } finally {
                    handshakePayload.release();
                }
            }
        }

        if (expected) {
            multipart.nextPart++;
            multipart.receivedBytes += dataBytes;
            if (multipart.nextPart == multipart.parts || multipart.receivedBytes == multipart.totalLength) {
                events.add(new Event(direction, multipart.receivedBytes == multipart.totalLength
                                ? "multipart_complete"
                                : "multipart_length_mismatch",
                        multipart.wrappedChannel, -1, multipart.receivedBytes, stage,
                        multipart.receivedBytes == multipart.totalLength));
                clearMultipart();
            }
        } else {
            clearMultipart();
        }
        return List.copyOf(events);
    }

    private Event parseHandshakePayload(Direction direction, String channel, ByteBuf payload, int packetBytes) {
        if (!payload.isReadable()) {
            return new Event(direction, "empty_handshake", channel, -1, packetBytes, stage, false);
        }
        var discriminator = payload.readUnsignedByte();
        var type = switch (discriminator) {
            case 0 -> "server_hello";
            case 1 -> "client_hello";
            case 2 -> "mod_list";
            case 3 -> "registry_data";
            case 254 -> "handshake_reset";
            case 255 -> "handshake_ack";
            default -> "unknown_handshake";
        };
        var expected = expected(direction, discriminator);
        if (expected) {
            updateStage(direction, discriminator, payload);
        }
        return new Event(direction, type, channel, discriminator, payload.readableBytes(), stage, expected);
    }

    private void updateStage(Direction direction, int discriminator, ByteBuf payload) {
        if (direction == Direction.CLIENTBOUND && discriminator == 254) {
            resetHandshakeState(true);
            stage = Stage.RESET;
            clientPhase = ClientPhase.RESET;
            backendPhase = BackendPhase.RESET;
            return;
        }
        if (direction == Direction.CLIENTBOUND && discriminator == 0) {
            advanceClientPhase(ClientPhase.SERVER_HELLO_RECEIVED);
            advanceBackendPhase(BackendPhase.SERVER_HELLO_SENT);
            advance(Stage.SERVER_HELLO);
            return;
        }
        if (direction == Direction.SERVERBOUND && discriminator == 1) {
            advanceClientPhase(ClientPhase.CLIENT_HELLO_SENT);
            advance(Stage.CLIENT_HELLO);
            return;
        }
        if (discriminator == 2) {
            var mods = scanModList(payload);
            if (direction == Direction.SERVERBOUND) {
                clientMods = mods;
                advanceClientPhase(ClientPhase.MOD_LIST_SENT);
            } else {
                serverMods = mods;
                advanceBackendPhase(BackendPhase.MOD_LIST_SENT);
            }
            advance(direction == Direction.SERVERBOUND ? Stage.CLIENT_MOD_LIST : Stage.SERVER_MOD_LIST);
            return;
        }
        if (direction == Direction.CLIENTBOUND && discriminator == 3) {
            clientboundRegistryPackets++;
            clientboundRegistryBytes += payload.readableBytes();
            advanceBackendPhase(BackendPhase.REGISTRY_DATA_SENT);
            advance(Stage.REGISTRY_DATA);
            return;
        }
        if (discriminator == 255 && payload.isReadable()) {
            updateAckStage(direction, payload.readUnsignedByte());
        }
    }

    private boolean expected(Direction direction, int discriminator) {
        if (discriminator == 254 && direction == Direction.CLIENTBOUND) {
            return true;
        }
        return switch (stage) {
            case IDLE, CHANNELS_REGISTERED -> direction == Direction.CLIENTBOUND && discriminator == 0;
            case SERVER_HELLO -> direction == Direction.SERVERBOUND && (discriminator == 1 || discriminator == 2);
            case CLIENT_HELLO -> direction == Direction.SERVERBOUND && discriminator == 2;
            case CLIENT_MOD_LIST -> direction == Direction.CLIENTBOUND && discriminator == 2;
            case SERVER_MOD_LIST -> direction == Direction.SERVERBOUND && discriminator == 255;
            case WAITING_SERVER_DATA, REGISTRY_DATA -> direction == Direction.CLIENTBOUND && discriminator == 3
                    || direction == Direction.SERVERBOUND && discriminator == 255;
            case WAITING_SERVER_COMPLETE -> direction == Direction.SERVERBOUND && discriminator == 255
                    || direction == Direction.CLIENTBOUND && discriminator == 255;
            case WAITING_CACK -> direction == Direction.SERVERBOUND && discriminator == 255;
            case PENDING_COMPLETE -> direction == Direction.CLIENTBOUND && discriminator == 255;
            case COMPLETE -> true;
            case RESET -> direction == Direction.CLIENTBOUND && discriminator == 0;
        };
    }

    private void updateAckStage(Direction direction, int phase) {
        if (direction == Direction.SERVERBOUND) {
            if (phase == 2) {
                advanceClientPhase(ClientPhase.WAITING_SERVER_DATA);
                advance(Stage.WAITING_SERVER_DATA);
            } else if (phase == 3) {
                advanceClientPhase(ClientPhase.WAITING_SERVER_COMPLETE);
                advance(Stage.WAITING_SERVER_COMPLETE);
            } else if (phase == 4) {
                advanceClientPhase(ClientPhase.PENDING_COMPLETE);
                advance(Stage.PENDING_COMPLETE);
            } else if (phase == 5) {
                advanceClientPhase(ClientPhase.COMPLETE);
                advance(Stage.COMPLETE);
            }
            return;
        }
        if (phase == 2) {
            advanceBackendPhase(BackendPhase.WAITING_CACK);
            advance(Stage.WAITING_CACK);
        } else if (phase == 3) {
            advanceBackendPhase(BackendPhase.COMPLETE);
            advance(Stage.COMPLETE);
        }
    }

    private void advance(Stage next) {
        if (stage == Stage.RESET && next != Stage.RESET) {
            stage = next;
            return;
        }
        if (next.ordinal() > stage.ordinal() || next == Stage.RESET) {
            stage = next;
        }
    }

    private void advanceClientPhase(ClientPhase next) {
        if (clientPhase == ClientPhase.RESET && next != ClientPhase.RESET) {
            clientPhase = next;
            return;
        }
        if (next.ordinal() > clientPhase.ordinal() || next == ClientPhase.RESET) {
            clientPhase = next;
        }
    }

    private void advanceBackendPhase(BackendPhase next) {
        if (backendPhase == BackendPhase.RESET && next != BackendPhase.RESET) {
            backendPhase = next;
            return;
        }
        if (next.ordinal() > backendPhase.ordinal() || next == BackendPhase.RESET) {
            backendPhase = next;
        }
    }

    int clientModCount() {
        return clientMods.size();
    }

    int serverModCount() {
        return serverMods.size();
    }

    int clientboundRegistryPackets() {
        return clientboundRegistryPackets;
    }

    long clientboundRegistryBytes() {
        return clientboundRegistryBytes;
    }

    Map<String, String> clientMods() {
        return clientMods;
    }

    Map<String, String> serverMods() {
        return serverMods;
    }

    ClientPhase clientPhase() {
        return clientPhase;
    }

    BackendPhase backendPhase() {
        return backendPhase;
    }

    private static Map<String, String> scanModList(ByteBuf payload) {
        if (!payload.isReadable()) {
            return Map.of();
        }
        var count = MinecraftProtocolCodec.readVarInt(payload);
        if (count < 0 || count > MAX_MOD_LIST_ENTRIES_TO_SCAN) {
            throw new IllegalArgumentException("invalid FML mod list size");
        }
        var mods = new LinkedHashMap<String, String>(Math.min(count, 256));
        for (var index = 0; index < count && payload.isReadable(); index++) {
            var modId = readString(payload, 256);
            var version = readString(payload, 256);
            mods.put(modId, version);
        }
        return Collections.unmodifiableMap(mods);
    }

    private static List<String> registeredChannels(ByteBuf payload) {
        if (!payload.isReadable()) {
            return List.of();
        }
        var channels = new ArrayList<String>();
        var start = payload.readerIndex();
        var end = payload.writerIndex();
        for (var index = start; index <= end; index++) {
            if (index == end || payload.getByte(index) == 0) {
                if (index > start) {
                    channels.add(payload.toString(start, index - start, StandardCharsets.UTF_8));
                }
                start = index + 1;
            }
        }
        return List.copyOf(channels);
    }

    private void resetHandshakeState(boolean keepCurrentClientboundPending) {
        clientMods = Map.of();
        serverMods = Map.of();
        clientboundRegistryPackets = 0;
        clientboundRegistryBytes = 0;
        clearPending(serverboundPending);
        clearPending(serverboundCompressedPending);
        if (!keepCurrentClientboundPending) {
            clearPending(clientboundPending);
            clearPending(clientboundCompressedPending);
        }
        clearMultipart();
    }

    private static void clearPending(ByteBuf pending) {
        if (pending.refCnt() > 0) {
            pending.clear();
        }
    }

    private void clearMultipart() {
        clientboundMultipart = null;
    }

    private static boolean forgeChannel(String channel) {
        return "REGISTER".equals(channel)
                || "FML|HS".equals(channel)
                || "FML|MP".equals(channel)
                || "FML".equals(channel)
                || "FORGE".equals(channel);
    }

    private static String readString(ByteBuf input, int maxCharacters) {
        return MinecraftProtocolCodec.readString(input, maxCharacters);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (serverboundPending.refCnt() > 0) {
            serverboundPending.release();
        }
        if (clientboundPending.refCnt() > 0) {
            clientboundPending.release();
        }
        if (serverboundCompressedPending.refCnt() > 0) {
            serverboundCompressedPending.release();
        }
        if (clientboundCompressedPending.refCnt() > 0) {
            clientboundCompressedPending.release();
        }
        serverboundPending = Unpooled.EMPTY_BUFFER;
        clientboundPending = Unpooled.EMPTY_BUFFER;
        serverboundCompressedPending = Unpooled.EMPTY_BUFFER;
        clientboundCompressedPending = Unpooled.EMPTY_BUFFER;
        compressionCodec.close();
        clearMultipart();
    }

    enum Direction {
        SERVERBOUND,
        CLIENTBOUND
    }

    enum Stage {
        IDLE,
        CHANNELS_REGISTERED,
        SERVER_HELLO,
        CLIENT_HELLO,
        CLIENT_MOD_LIST,
        SERVER_MOD_LIST,
        WAITING_SERVER_DATA,
        REGISTRY_DATA,
        WAITING_SERVER_COMPLETE,
        WAITING_CACK,
        PENDING_COMPLETE,
        COMPLETE,
        RESET
    }

    enum ClientPhase {
        NOT_STARTED,
        RESET,
        CHANNELS_REGISTERED,
        SERVER_HELLO_RECEIVED,
        CLIENT_HELLO_SENT,
        MOD_LIST_SENT,
        WAITING_SERVER_DATA,
        WAITING_SERVER_COMPLETE,
        PENDING_COMPLETE,
        COMPLETE
    }

    enum BackendPhase {
        NOT_STARTED,
        RESET,
        CHANNELS_REGISTERED,
        SERVER_HELLO_SENT,
        MOD_LIST_SENT,
        REGISTRY_DATA_SENT,
        WAITING_CACK,
        COMPLETE
    }

    record Event(Direction direction, String type, String channel, int discriminator, int payloadBytes, Stage stage, boolean expected) {
    }

    private static final class MultipartState {
        private final String wrappedChannel;
        private final int parts;
        private final int totalLength;
        private int nextPart;
        private int receivedBytes;

        private MultipartState(String wrappedChannel, int parts, int totalLength) {
            this.wrappedChannel = wrappedChannel;
            this.parts = parts;
            this.totalLength = totalLength;
        }
    }
}

package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import dev.strataproxy.codec.minecraft.MinecraftCompressionCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

final class MinecraftLegacyClientStateTracker implements AutoCloseable {
    private static final int DEFAULT_MAX_PLAYER_LIST_ENTRIES = 16_384;
    private static final int DEFAULT_MAX_OBJECTIVES = 4_096;
    private static final int DEFAULT_MAX_TEAMS = 4_096;

    private final int maxFrameBytes;
    private final int maxPlayerListEntries;
    private final int maxObjectives;
    private final int maxTeams;
    private final Set<String> legacyPlayerListNames = new LinkedHashSet<>();
    private final Set<UUID> playerListUuids = new LinkedHashSet<>();
    private final Set<String> objectives = new LinkedHashSet<>();
    private final Set<String> teams = new LinkedHashSet<>();
    private final MinecraftCompressionCodec compressionCodec = new MinecraftCompressionCodec();
    private ByteBuf pending = Unpooled.buffer();
    private ByteBuf compressedPending = Unpooled.buffer();
    private boolean closed;

    MinecraftLegacyClientStateTracker(int maxFrameBytes) {
        this(maxFrameBytes, DEFAULT_MAX_PLAYER_LIST_ENTRIES, DEFAULT_MAX_OBJECTIVES, DEFAULT_MAX_TEAMS);
    }

    MinecraftLegacyClientStateTracker(
            int maxFrameBytes,
            int maxPlayerListEntries,
            int maxObjectives,
            int maxTeams) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        if (maxPlayerListEntries <= 0 || maxObjectives <= 0 || maxTeams <= 0) {
            throw new IllegalArgumentException("tracked state limits must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.maxPlayerListEntries = maxPlayerListEntries;
        this.maxObjectives = maxObjectives;
        this.maxTeams = maxTeams;
    }

    void observe(ByteBuf input, MinecraftProtocolProfile profile) {
        if (closed || input == null || !input.isReadable() || !supported(profile)) {
            return;
        }
        if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
            pending.clear();
            throw new IllegalArgumentException("legacy client state tracker exceeded maximum frame size");
        }
        pending.writeBytes(input, input.readerIndex(), input.readableBytes());
        while (pending.isReadable()) {
            var probe = MinecraftProtocolCodec.probeFrame(pending, maxFrameBytes);
            if (!probe.complete()) {
                break;
            }
            var frame = pending.readRetainedSlice(probe.totalBytes());
            try {
                observeFrame(frame, probe.varIntBytes(), profile);
            } finally {
                frame.release();
            }
            pending.discardReadBytes();
        }
    }

    void observeCompressed(ByteBufAllocator allocator, ByteBuf input, int threshold, MinecraftProtocolProfile profile) {
        if (closed || input == null || !input.isReadable() || !supported(profile)) {
            return;
        }
        if (threshold < 0) {
            throw new IllegalArgumentException("compression threshold must be non-negative");
        }
        if (compressedPending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
            compressedPending.clear();
            throw new IllegalArgumentException("legacy client state tracker compressed frame exceeded maximum size");
        }
        compressedPending.writeBytes(input, input.readerIndex(), input.readableBytes());
        while (compressedPending.isReadable()) {
            var probe = MinecraftProtocolCodec.probeFrame(compressedPending, maxFrameBytes);
            if (!probe.complete()) {
                break;
            }
            var frame = compressedPending.readRetainedSlice(probe.totalBytes());
            ByteBuf decoded = null;
            try {
                decoded = compressionCodec.decodeFrame(allocator, frame, threshold, maxFrameBytes);
                observePacket(decoded, profile);
            } finally {
                if (decoded != null) {
                    decoded.release();
                }
                frame.release();
            }
            compressedPending.discardReadBytes();
        }
    }

    ByteBuf clearFrames(ByteBufAllocator allocator, MinecraftProtocolProfile profile) {
        if (!supported(profile) || empty()) {
            return Unpooled.EMPTY_BUFFER;
        }
        var output = allocator.buffer();
        try {
            writePlayerListRemoveFrames(allocator, output, profile);
            for (var objective : objectives) {
                writeScoreboardObjectiveRemoveFrame(allocator, output, profile, objective);
            }
            for (var team : teams) {
                writeTeamRemoveFrame(allocator, output, profile, team);
            }
            clearTrackedState();
            return output;
        } catch (RuntimeException exception) {
            output.release();
            throw exception;
        }
    }

    int playerListCount() {
        return legacyPlayerListNames.size() + playerListUuids.size();
    }

    int objectiveCount() {
        return objectives.size();
    }

    int teamCount() {
        return teams.size();
    }

    private void observeFrame(ByteBuf frame, int varIntBytes, MinecraftProtocolProfile profile) {
        var packet = frame.retainedDuplicate();
        try {
            packet.skipBytes(varIntBytes);
            observePacket(packet, profile);
        } catch (RuntimeException ignored) {
        } finally {
            packet.release();
        }
    }

    private void observePacket(ByteBuf packet, MinecraftProtocolProfile profile) {
        var packetId = MinecraftProtocolCodec.readVarInt(packet);
        if (profile.clientboundPlayerListItemPacketId().isPresent()
                && packetId == profile.clientboundPlayerListItemPacketId().getAsInt()) {
            observePlayerListItem(packet, profile);
            return;
        }
        if (profile.clientboundScoreboardObjectivePacketId().isPresent()
                && packetId == profile.clientboundScoreboardObjectivePacketId().getAsInt()) {
            observeScoreboardObjective(packet, profile);
            return;
        }
        if (profile.clientboundTeamPacketId().isPresent()
                && packetId == profile.clientboundTeamPacketId().getAsInt()) {
            observeTeam(packet);
        }
    }

    private void observePlayerListItem(ByteBuf packet, MinecraftProtocolProfile profile) {
        switch (profile.playerListItemLayout()) {
            case LEGACY_NAME -> {
                var name = MinecraftProtocolCodec.readString(packet, 16);
                var online = packet.readBoolean();
                packet.readShort();
                if (online) {
                    addBounded(legacyPlayerListNames, name, maxPlayerListEntries, "legacy player list");
                } else {
                    legacyPlayerListNames.remove(name);
                }
                return;
            }
            case UUID_ACTION -> {
            }
            case NONE -> {
                return;
            }
        }

        var action = MinecraftProtocolCodec.readVarInt(packet);
        var count = MinecraftProtocolCodec.readVarInt(packet);
        if (count < 0 || count > 1024) {
            throw new IllegalArgumentException("player list item count out of bounds: " + count);
        }
        for (var i = 0; i < count; i++) {
            var uuid = new UUID(packet.readLong(), packet.readLong());
            if (action == 0) {
                skipModernPlayerListAdd(packet);
                addBounded(playerListUuids, uuid, maxPlayerListEntries, "modern player list");
            } else if (action == 4) {
                playerListUuids.remove(uuid);
            } else {
                skipModernPlayerListUpdate(packet, action);
            }
        }
    }

    private void observeScoreboardObjective(ByteBuf packet, MinecraftProtocolProfile profile) {
        var name = MinecraftProtocolCodec.readString(packet, 16);
        int action;
        if (profile.scoreboardObjectiveLayout() == MinecraftProtocolProfile.ScoreboardObjectiveLayout.NAME_VALUE_ACTION) {
            MinecraftProtocolCodec.readString(packet, 32);
            action = packet.readUnsignedByte();
        } else if (profile.scoreboardObjectiveLayout() == MinecraftProtocolProfile.ScoreboardObjectiveLayout.NAME_ACTION) {
            action = packet.readUnsignedByte();
        } else {
            return;
        }
        if (action == 0) {
            addBounded(objectives, name, maxObjectives, "scoreboard objectives");
        } else if (action == 1) {
            objectives.remove(name);
        }
    }

    private void skipModernPlayerListAdd(ByteBuf packet) {
        MinecraftProtocolCodec.readString(packet, 16);
        var properties = MinecraftProtocolCodec.readVarInt(packet);
        if (properties < 0 || properties > 64) {
            throw new IllegalArgumentException("player list properties out of bounds: " + properties);
        }
        for (var i = 0; i < properties; i++) {
            MinecraftProtocolCodec.readString(packet, 32767);
            MinecraftProtocolCodec.readString(packet, 32767);
            if (packet.readBoolean()) {
                MinecraftProtocolCodec.readString(packet, 32767);
            }
        }
        MinecraftProtocolCodec.readVarInt(packet);
        MinecraftProtocolCodec.readVarInt(packet);
        if (packet.readBoolean()) {
            MinecraftProtocolCodec.readString(packet, 32767);
        }
    }

    private void skipModernPlayerListUpdate(ByteBuf packet, int action) {
        switch (action) {
            case 1, 2 -> MinecraftProtocolCodec.readVarInt(packet);
            case 3 -> {
                if (packet.readBoolean()) {
                    MinecraftProtocolCodec.readString(packet, 32767);
                }
            }
            default -> throw new IllegalArgumentException("unknown player list action: " + action);
        }
    }

    private void writePlayerListRemoveFrames(
            ByteBufAllocator allocator,
            ByteBuf output,
            MinecraftProtocolProfile profile) {
        if (profile.clientboundPlayerListItemPacketId().isEmpty()) {
            return;
        }
        if (profile.playerListItemLayout() == MinecraftProtocolProfile.PlayerListItemLayout.LEGACY_NAME) {
            for (var name : legacyPlayerListNames) {
                writeLegacyPlayerListRemoveFrame(allocator, output, profile, name);
            }
        } else if (profile.playerListItemLayout() == MinecraftProtocolProfile.PlayerListItemLayout.UUID_ACTION
                && !playerListUuids.isEmpty()) {
            writeModernPlayerListRemoveFrame(allocator, output, profile);
        }
    }

    private void writeLegacyPlayerListRemoveFrame(
            ByteBufAllocator allocator,
            ByteBuf output,
            MinecraftProtocolProfile profile,
            String name) {
        var packet = allocator.buffer();
        try {
            MinecraftVarInts.write(packet, profile.clientboundPlayerListItemPacketId().orElseThrow());
            writeString(packet, name);
            packet.writeBoolean(false);
            packet.writeShort(0);
            writeFrame(output, packet);
        } finally {
            packet.release();
        }
    }

    private void writeModernPlayerListRemoveFrame(
            ByteBufAllocator allocator,
            ByteBuf output,
            MinecraftProtocolProfile profile) {
        var packet = allocator.buffer();
        try {
            MinecraftVarInts.write(packet, profile.clientboundPlayerListItemPacketId().orElseThrow());
            MinecraftVarInts.write(packet, 4);
            MinecraftVarInts.write(packet, playerListUuids.size());
            for (var uuid : playerListUuids) {
                packet.writeLong(uuid.getMostSignificantBits());
                packet.writeLong(uuid.getLeastSignificantBits());
            }
            writeFrame(output, packet);
        } finally {
            packet.release();
        }
    }

    private void observeTeam(ByteBuf packet) {
        var name = MinecraftProtocolCodec.readString(packet, 16);
        var mode = packet.readUnsignedByte();
        if (mode == 0) {
            addBounded(teams, name, maxTeams, "scoreboard teams");
        } else if (mode == 1) {
            teams.remove(name);
        }
    }

    private static void writeScoreboardObjectiveRemoveFrame(
            ByteBufAllocator allocator,
            ByteBuf output,
            MinecraftProtocolProfile profile,
            String name) {
        var packet = allocator.buffer();
        try {
            MinecraftVarInts.write(packet, profile.clientboundScoreboardObjectivePacketId().orElseThrow());
            writeString(packet, name);
            if (profile.scoreboardObjectiveLayout() == MinecraftProtocolProfile.ScoreboardObjectiveLayout.NAME_VALUE_ACTION) {
                writeString(packet, "");
                packet.writeByte(1);
            } else if (profile.scoreboardObjectiveLayout() == MinecraftProtocolProfile.ScoreboardObjectiveLayout.NAME_ACTION) {
                packet.writeByte(1);
            } else {
                return;
            }
            writeFrame(output, packet);
        } finally {
            packet.release();
        }
    }

    private static void writeTeamRemoveFrame(
            ByteBufAllocator allocator,
            ByteBuf output,
            MinecraftProtocolProfile profile,
            String name) {
        var packet = allocator.buffer();
        try {
            MinecraftVarInts.write(packet, profile.clientboundTeamPacketId().orElseThrow());
            writeString(packet, name);
            packet.writeByte(1);
            writeFrame(output, packet);
        } finally {
            packet.release();
        }
    }

    private static void writeFrame(ByteBuf output, ByteBuf packet) {
        MinecraftVarInts.write(output, packet.readableBytes());
        output.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static boolean supported(MinecraftProtocolProfile profile) {
        return profile != null
                && (profile.clientboundPlayerListItemPacketId().isPresent()
                        || profile.clientboundScoreboardObjectivePacketId().isPresent()
                        || profile.clientboundTeamPacketId().isPresent());
    }

    private boolean empty() {
        return legacyPlayerListNames.isEmpty()
                && playerListUuids.isEmpty()
                && objectives.isEmpty()
                && teams.isEmpty();
    }

    private <T> void addBounded(Set<T> values, T value, int maxValues, String label) {
        if (!values.contains(value) && values.size() >= maxValues) {
            clearTrackedState();
            throw new IllegalStateException("legacy client state tracker exceeded " + label + " limit");
        }
        values.add(value);
    }

    private void clearTrackedState() {
        legacyPlayerListNames.clear();
        playerListUuids.clear();
        objectives.clear();
        teams.clear();
    }

    @Override
    public void close() {
        closed = true;
        clearTrackedState();
        if (pending.refCnt() > 0) {
            pending.release();
        }
        if (compressedPending.refCnt() > 0) {
            compressedPending.release();
        }
        pending = Unpooled.EMPTY_BUFFER;
        compressedPending = Unpooled.EMPTY_BUFFER;
        compressionCodec.close();
    }
}

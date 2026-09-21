package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCompressionCodec;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftLegacyClientStateTrackerTest {
    @Test
    void tracksAndClears1710ObjectivesAndTeams() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftLegacyClientStateTracker(4096);
        var frames = combine(playerList1710("Alex", true), objective1710("sidebar", 0), team("red", 0));
        try {
            tracker.observe(frames, profile);
            assertEquals(1, tracker.playerListCount());
            assertEquals(1, tracker.objectiveCount());
            assertEquals(1, tracker.teamCount());

            var cleanup = tracker.clearFrames(UnpooledByteBufAllocator.DEFAULT, profile);
            try {
                var playerList = payload(cleanup);
                try {
                    assertEquals(0x38, MinecraftVarInts.read(playerList));
                    assertEquals("Alex", readString(playerList));
                    assertEquals(0, playerList.readUnsignedByte());
                    assertEquals(0, playerList.readShort());
                } finally {
                    playerList.release();
                }
                var objective = payload(cleanup);
                try {
                    assertEquals(0x3B, MinecraftVarInts.read(objective));
                    assertEquals("sidebar", readString(objective));
                    assertEquals(1, objective.readUnsignedByte());
                    assertEquals(0, objective.readableBytes());
                } finally {
                    objective.release();
                }
                var team = payload(cleanup);
                try {
                    assertEquals(0x3E, MinecraftVarInts.read(team));
                    assertEquals("red", readString(team));
                    assertEquals(1, team.readUnsignedByte());
                } finally {
                    team.release();
                }
                assertEquals(0, cleanup.readableBytes());
                assertEquals(0, tracker.playerListCount());
                assertEquals(0, tracker.objectiveCount());
                assertEquals(0, tracker.teamCount());
            } finally {
                cleanup.release();
            }
        } finally {
            frames.release();
            tracker.close();
        }
    }

    @Test
    void tracksAndClears18ObjectivesAndTeams() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var tracker = new MinecraftLegacyClientStateTracker(4096);
        var uuid = UUID.fromString("00000000-0000-0000-0000-000000000123");
        var frames = combine(playerList18(uuid, 0), objective18("list", 0), team("blue", 0));
        try {
            tracker.observe(frames, profile);

            var cleanup = tracker.clearFrames(UnpooledByteBufAllocator.DEFAULT, profile);
            try {
                var playerList = payload(cleanup);
                try {
                    assertEquals(0x38, MinecraftVarInts.read(playerList));
                    assertEquals(4, MinecraftVarInts.read(playerList));
                    assertEquals(1, MinecraftVarInts.read(playerList));
                    assertEquals(uuid.getMostSignificantBits(), playerList.readLong());
                    assertEquals(uuid.getLeastSignificantBits(), playerList.readLong());
                } finally {
                    playerList.release();
                }
                var objective = payload(cleanup);
                try {
                    assertEquals(0x3B, MinecraftVarInts.read(objective));
                    assertEquals("list", readString(objective));
                    assertEquals(1, objective.readUnsignedByte());
                } finally {
                    objective.release();
                }
                var team = payload(cleanup);
                try {
                    assertEquals(0x3E, MinecraftVarInts.read(team));
                    assertEquals("blue", readString(team));
                    assertEquals(1, team.readUnsignedByte());
                } finally {
                    team.release();
                }
                assertEquals(0, cleanup.readableBytes());
            } finally {
                cleanup.release();
            }
        } finally {
            frames.release();
            tracker.close();
        }
    }

    @Test
    void tracksCompressed18PlayerListEntries() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var uuid = UUID.fromString("00000000-0000-0000-0000-000000000456");
        var tracker = new MinecraftLegacyClientStateTracker(4096);
        try (var codec = new MinecraftCompressionCodec()) {
            var packet = playerList18Packet(uuid, 0);
            var compressed = codec.encodeFrame(UnpooledByteBufAllocator.DEFAULT, packet, 0);
            packet.release();
            try {
                tracker.observeCompressed(UnpooledByteBufAllocator.DEFAULT, compressed, 0, profile);
            } finally {
                compressed.release();
            }

            var cleanup = tracker.clearFrames(UnpooledByteBufAllocator.DEFAULT, profile);
            try {
                var playerList = payload(cleanup);
                try {
                    assertEquals(0x38, MinecraftVarInts.read(playerList));
                    assertEquals(4, MinecraftVarInts.read(playerList));
                    assertEquals(1, MinecraftVarInts.read(playerList));
                    assertEquals(uuid.getMostSignificantBits(), playerList.readLong());
                    assertEquals(uuid.getLeastSignificantBits(), playerList.readLong());
                } finally {
                    playerList.release();
                }
                assertEquals(0, cleanup.readableBytes());
            } finally {
                cleanup.release();
            }
        } finally {
            tracker.close();
        }
    }

    @Test
    void removePacketsDeleteTrackedState() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftLegacyClientStateTracker(4096);
        var frames = combine(
                playerList1710("Alex", true),
                objective1710("sidebar", 0),
                team("red", 0),
                playerList1710("Alex", false),
                objective1710("sidebar", 1),
                team("red", 1));
        try {
            tracker.observe(frames, profile);

            var cleanup = tracker.clearFrames(UnpooledByteBufAllocator.DEFAULT, profile);
            try {
                assertEquals(0, cleanup.readableBytes());
                assertEquals(0, tracker.playerListCount());
                assertEquals(0, tracker.objectiveCount());
                assertEquals(0, tracker.teamCount());
            } finally {
                cleanup.release();
            }
        } finally {
            frames.release();
            tracker.close();
        }
    }

    @Test
    void clearsTrackedStateWhenPlayerListLimitIsExceeded() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftLegacyClientStateTracker(4096, 1, 4, 4);
        var frames = combine(playerList1710("Alex", true), playerList1710("Steve", true));
        try {
            tracker.observe(frames, profile);

            assertEquals(0, tracker.playerListCount());
            var cleanup = tracker.clearFrames(UnpooledByteBufAllocator.DEFAULT, profile);
            try {
                assertEquals(0, cleanup.readableBytes());
            } finally {
                cleanup.release();
            }
        } finally {
            frames.release();
            tracker.close();
        }
    }

    @Test
    void clearsTrackedStateWhenScoreboardLimitIsExceeded() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftLegacyClientStateTracker(4096, 4, 1, 1);
        var frames = combine(
                objective1710("one", 0),
                objective1710("two", 0),
                team("red", 0),
                team("blue", 0));
        try {
            tracker.observe(frames, profile);

            assertEquals(0, tracker.objectiveCount());
            assertEquals(0, tracker.teamCount());
            var cleanup = tracker.clearFrames(UnpooledByteBufAllocator.DEFAULT, profile);
            try {
                assertEquals(0, cleanup.readableBytes());
            } finally {
                cleanup.release();
            }
        } finally {
            frames.release();
            tracker.close();
        }
    }

    private static ByteBuf playerList1710(String name, boolean online) {
        var packet = Unpooled.buffer();
        MinecraftVarInts.write(packet, 0x38);
        writeString(packet, name);
        packet.writeBoolean(online);
        packet.writeShort(42);
        return frame(packet);
    }

    private static ByteBuf playerList18(UUID uuid, int action) {
        return frame(playerList18Packet(uuid, action));
    }

    private static ByteBuf playerList18Packet(UUID uuid, int action) {
        var packet = Unpooled.buffer();
        MinecraftVarInts.write(packet, 0x38);
        MinecraftVarInts.write(packet, action);
        MinecraftVarInts.write(packet, 1);
        packet.writeLong(uuid.getMostSignificantBits());
        packet.writeLong(uuid.getLeastSignificantBits());
        if (action == 0) {
            writeString(packet, "Alex");
            MinecraftVarInts.write(packet, 0);
            MinecraftVarInts.write(packet, 0);
            MinecraftVarInts.write(packet, 42);
            packet.writeBoolean(false);
        }
        return packet;
    }

    private static ByteBuf objective1710(String name, int mode) {
        var packet = Unpooled.buffer();
        MinecraftVarInts.write(packet, 0x3B);
        writeString(packet, name);
        packet.writeByte(mode);
        return frame(packet);
    }

    private static ByteBuf objective18(String name, int mode) {
        var packet = Unpooled.buffer();
        MinecraftVarInts.write(packet, 0x3B);
        writeString(packet, name);
        packet.writeByte(mode);
        if (mode == 0 || mode == 2) {
            writeString(packet, name);
            writeString(packet, "integer");
        }
        return frame(packet);
    }

    private static ByteBuf team(String name, int mode) {
        var packet = Unpooled.buffer();
        MinecraftVarInts.write(packet, 0x3E);
        writeString(packet, name);
        packet.writeByte(mode);
        return frame(packet);
    }

    private static ByteBuf combine(ByteBuf... frames) {
        var output = Unpooled.buffer();
        for (var frame : frames) {
            output.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
            frame.release();
        }
        return output;
    }

    private static ByteBuf frame(ByteBuf packet) {
        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, packet.readableBytes());
        frame.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
        packet.release();
        return frame;
    }

    private static ByteBuf payload(ByteBuf frames) {
        var length = MinecraftVarInts.read(frames);
        return frames.readRetainedSlice(length);
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static String readString(ByteBuf input) {
        var bytes = new byte[MinecraftVarInts.read(input)];
        input.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

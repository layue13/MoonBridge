package dev.strataproxy.core.session;

import dev.strataproxy.core.protocol.Minecraft1710PlayPackets;
import dev.strataproxy.core.protocol.PacketStreamTap;
import dev.strataproxy.core.protocol.ProtocolVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlayObservationTest {
    @Test
    void fragmentedAndCoalescedFramesReachObserverOnce() {
        var seen = new ArrayList<Integer>();
        try (var tap = new PacketStreamTap(1024, packet -> seen.add(ProtocolVarInt.read(packet)))) {
            ByteBuf joined = Unpooled.buffer();
            try {
                joined.writeByte(1).writeByte(7).writeByte(1).writeByte(8);
                tap.accept(joined.readSlice(1));
                assertTrue(seen.isEmpty());
                tap.accept(joined.readSlice(2));
                assertEquals(1, seen.size());
                tap.accept(joined.readSlice(1));
                assertEquals(java.util.List.of(7, 8), seen);
            } finally { joined.release(); }
        }
    }

    @Test
    void waitsForBothForgeCompletionDirections() {
        try (var state = new PlayObservation()) {
            ByteBuf join = Unpooled.buffer();
            try {
                ProtocolVarInt.write(join, 1);
                join.writeInt(3).writeByte(0).writeByte(0).writeByte(1).writeByte(20);
                byte[] level = "default".getBytes(StandardCharsets.UTF_8);
                ProtocolVarInt.write(join, level.length);
                join.writeBytes(level);
                state.observePacket(true, join);
            } finally { join.release(); }
            sendFml(state, true, 0, 2, 0, 0, 1, 0); // ServerHello, override dimension 256.
            assertTrue(state.forgeSeen());
            assertEquals(256, state.dimension().orElseThrow());
            sendFml(state, true, 0xFF, 3);
            assertFalse(state.ready().isDone());
            sendFml(state, false, 0xFF, 5);
            assertTrue(state.ready().isDone());
        }
    }

    @Test
    void vanillaWorldPositionMarksReady() {
        try (var state = new PlayObservation()) {
            var join = new Minecraft1710PlayPackets.JoinGame(3, 0, -1, 1, "default");
            ByteBuf packet = Unpooled.buffer();
            try {
                ProtocolVarInt.write(packet, 1);
                packet.writeInt(join.entityId()).writeByte(join.gameMode()).writeByte(join.dimension())
                        .writeByte(join.difficulty()).writeByte(20);
                byte[] level = join.levelType().getBytes(StandardCharsets.UTF_8);
                ProtocolVarInt.write(packet, level.length);
                packet.writeBytes(level);
                state.observePacket(true, packet);
                assertFalse(state.ready().isDone());
                packet.clear();
                ProtocolVarInt.write(packet, 8);
                state.observePacket(true, packet);
                assertTrue(state.ready().isDone());
                assertFalse(state.forgeSeen());
            } finally { packet.release(); }
        }
    }

    private static void sendFml(PlayObservation state, boolean clientbound, int... payload) {
        ByteBuf packet = UnpooledByteBufAllocator.DEFAULT.buffer();
        try {
            ProtocolVarInt.write(packet, clientbound ? 0x3F : 0x17);
            byte[] channel = "FML|HS".getBytes(StandardCharsets.UTF_8);
            ProtocolVarInt.write(packet, channel.length);
            packet.writeBytes(channel);
            packet.writeShort(payload.length);
            for (int value : payload) packet.writeByte(value);
            state.observePacket(clientbound, packet);
        } finally { packet.release(); }
    }
}

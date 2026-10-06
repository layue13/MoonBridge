package dev.moonbridge.core.session.play;

import dev.moonbridge.core.protocol.Minecraft1710PlayPackets;
import dev.moonbridge.core.protocol.ProtocolVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlayObservationTest {
    @Test
    void completeWireFramesMarkVanillaReadyWithoutConsumingRelayBuffers() {
        try (var state = new PlayObservation()) {
            var join = new Minecraft1710PlayPackets.JoinGame(3, 0, -1, 1, "default");
            ByteBuf packet = Unpooled.buffer();
            try {
                writeJoin(packet, join);
                ByteBuf frame = Minecraft1710PlayPackets.frame(UnpooledByteBufAllocator.DEFAULT, packet);
                try {
                    int readerIndex = frame.readerIndex();
                    state.observeFrame(true, frame);
                    assertEquals(readerIndex, frame.readerIndex());
                    assertFalse(state.ready().isDone());
                } finally { frame.release(); }
                packet.clear();
                ProtocolVarInt.write(packet, 8);
                frame = Minecraft1710PlayPackets.frame(UnpooledByteBufAllocator.DEFAULT, packet);
                try {
                    state.observeFrame(true, frame);
                    assertTrue(state.ready().isDone());
                } finally { frame.release(); }
            } finally { packet.release(); }
        }
    }

    @Test
    void waitsForBothForgeCompletionDirections() {
        try (var state = new PlayObservation()) {
            sendJoin(state);
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
                writeJoin(packet, join);
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

    private static void sendJoin(PlayObservation state) {
        ByteBuf packet = Unpooled.buffer();
        try {
            writeJoin(packet, new Minecraft1710PlayPackets.JoinGame(3, 0, 0, 1, "default"));
            state.observePacket(true, packet);
        } finally { packet.release(); }
    }

    private static void writeJoin(ByteBuf packet, Minecraft1710PlayPackets.JoinGame join) {
        ProtocolVarInt.write(packet, 1);
        packet.writeInt(join.entityId()).writeByte(join.gameMode()).writeByte(join.dimension())
                .writeByte(join.difficulty()).writeByte(20);
        byte[] level = join.levelType().getBytes(StandardCharsets.UTF_8);
        ProtocolVarInt.write(packet, level.length);
        packet.writeBytes(level);
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

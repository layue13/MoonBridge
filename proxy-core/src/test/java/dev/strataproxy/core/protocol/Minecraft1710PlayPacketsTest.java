package dev.strataproxy.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Minecraft1710PlayPacketsTest {
    @Test
    void readsStockJoinGameAndForgeDimensionOverride() {
        ByteBuf join = Unpooled.buffer();
        ByteBuf hello = Unpooled.buffer();
        try {
            ProtocolVarInt.write(join, 1);
            join.writeInt(42).writeByte(8).writeByte(0).writeByte(2).writeByte(20);
            ProtocolStrings.write(join, "default", 16);
            var parsed = Minecraft1710PlayPackets.joinGame(join).orElseThrow();
            assertEquals(42, parsed.entityId());
            assertEquals(8, parsed.gameMode());
            assertEquals(0, parsed.dimension());
            assertEquals(2, parsed.difficulty());
            assertEquals("default", parsed.levelType());

            ProtocolVarInt.write(hello, 0x3F);
            ProtocolStrings.write(hello, "FML|HS", 20);
            hello.writeShort(6).writeByte(0).writeByte(2).writeInt(256);
            assertEquals(256, Minecraft1710PlayPackets.forgeServerHello(hello).orElseThrow().dimensionOverride());
        } finally {
            join.release();
            hello.release();
        }
    }

    @Test
    void readsExtendedVarShortForgeRegistryPayload() {
        int payloadLength = 65_536;
        ByteBuf packet = Unpooled.buffer();
        try {
            ProtocolVarInt.write(packet, Minecraft1710PlayPackets.SERVER_CUSTOM_PAYLOAD);
            ProtocolStrings.write(packet, "FML|HS", 20);
            packet.writeShort(0x8000); // Forge VarShort: extension flag, low 15 bits zero.
            packet.writeByte(2); // 2 << 15 = 65,536 bytes.
            packet.writeByte(3); // ModIdData discriminator.
            packet.writeZero(payloadLength - 1);
            int readerIndex = packet.readerIndex();
            var handshake = Minecraft1710PlayPackets.forgeHandshake(packet, true).orElseThrow();
            assertEquals(3, handshake.discriminator());
            assertEquals(readerIndex, packet.readerIndex());
        } finally {
            packet.release();
        }
    }

    @Test
    void alwaysSendsDimensionDetourThenTargetAndOneByteForgeReset() {
        var target = new Minecraft1710PlayPackets.JoinGame(7, 9, 0, 2, "default");
        ByteBuf respawns = Minecraft1710PlayPackets.respawnSequence(UnpooledByteBufAllocator.DEFAULT,
                target, 256);
        ByteBuf reset = Minecraft1710PlayPackets.forgeReset(UnpooledByteBufAllocator.DEFAULT);
        try {
            int firstLength = ProtocolVarInt.read(respawns);
            ByteBuf first = respawns.readSlice(firstLength);
            assertEquals(7, ProtocolVarInt.read(first));
            assertEquals(-1, first.readInt());
            assertEquals(2, first.readUnsignedByte());
            assertEquals(1, first.readUnsignedByte());
            assertEquals("default", ProtocolStrings.read(first, 16));

            int nextLength = ProtocolVarInt.read(respawns);
            ByteBuf next = respawns.readSlice(nextLength);
            assertEquals(7, ProtocolVarInt.read(next));
            assertEquals(256, next.readInt());
            assertTrue(!respawns.isReadable());

            assertEquals(reset.readableBytes() - 1, ProtocolVarInt.read(reset));
            assertEquals(0x3F, ProtocolVarInt.read(reset));
            assertEquals("FML|HS", ProtocolStrings.read(reset, 20));
            assertEquals(1, reset.readUnsignedShort());
            assertEquals(0xFE, reset.readUnsignedByte());
        } finally {
            respawns.release();
            reset.release();
        }
    }
}

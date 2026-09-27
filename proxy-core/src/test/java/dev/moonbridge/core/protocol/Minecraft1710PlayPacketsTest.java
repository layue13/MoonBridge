package dev.moonbridge.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Minecraft1710PlayPacketsTest {
    @Test
    void readsOnlyChatFramesWithoutChangingTheirReaderIndex() {
        ByteBuf chatBody = Unpooled.buffer();
        ByteBuf ordinaryBody = Unpooled.buffer();
        ByteBuf chat = null;
        ByteBuf ordinary = null;
        try {
            ProtocolVarInt.write(chatBody, Minecraft1710PlayPackets.CLIENT_CHAT);
            ProtocolStrings.write(chatBody, "/where 岛屿", 100);
            chat = Minecraft1710PlayPackets.frame(UnpooledByteBufAllocator.DEFAULT, chatBody);
            int start = chat.readerIndex();
            assertEquals("/where 岛屿", Minecraft1710PlayPackets.playerChat(chat).orElseThrow());
            assertEquals(start, chat.readerIndex());

            ProtocolVarInt.write(ordinaryBody, 0x03);
            ordinaryBody.writeByte(0x55);
            ordinary = Minecraft1710PlayPackets.frame(UnpooledByteBufAllocator.DEFAULT, ordinaryBody);
            start = ordinary.readerIndex();
            assertTrue(Minecraft1710PlayPackets.playerChat(ordinary).isEmpty());
            assertEquals(start, ordinary.readerIndex());
        } finally {
            chatBody.release();
            ordinaryBody.release();
            if (chat != null) chat.release();
            if (ordinary != null) ordinary.release();
        }
    }
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

    @Test
    void encodesEscapedUnicodeChatAndPlayDisconnectPayloads() {
        ByteBuf chatFrame = Minecraft1710PlayPackets.chatReply(UnpooledByteBufAllocator.DEFAULT,
                "引号 \" 和换行\n🙂");
        ByteBuf disconnect = Minecraft1710PlayPackets.disconnect(UnpooledByteBufAllocator.DEFAULT,
                "断开：\"维护\"\n稍后再试🙂");
        try {
            int chatLength = ProtocolVarInt.read(chatFrame);
            ByteBuf chat = chatFrame.readSlice(chatLength);
            assertEquals(Minecraft1710PlayPackets.SERVER_CHAT, ProtocolVarInt.read(chat));
            String component = ProtocolStrings.read(chat, 32767);
            assertTrue(component.contains("\\\""));
            assertTrue(component.contains("\\n"));
            assertTrue(component.contains("🙂"));

            assertEquals(Minecraft1710PlayPackets.SERVER_DISCONNECT, ProtocolVarInt.read(disconnect));
            String reason = ProtocolStrings.read(disconnect, 32767);
            assertTrue(reason.contains("\\\""));
            assertTrue(reason.contains("\\n"));
            assertTrue(reason.contains("🙂"));
        } finally {
            chatFrame.release();
            disconnect.release();
        }
    }

    @Test
    void textBoundsCountUnicodeCodePoints() {
        String withinLimit = "🙂".repeat(1024);
        ByteBuf accepted = Minecraft1710PlayPackets.chatReply(UnpooledByteBufAllocator.DEFAULT, withinLimit);
        try {
            assertTrue(accepted.isReadable());
        } finally {
            accepted.release();
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Minecraft1710PlayPackets.chatReply(UnpooledByteBufAllocator.DEFAULT, withinLimit + "🙂"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Minecraft1710PlayPackets.disconnect(UnpooledByteBufAllocator.DEFAULT, " \n"));
    }
}

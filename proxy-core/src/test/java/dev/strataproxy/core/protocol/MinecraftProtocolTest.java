package dev.strataproxy.core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

class MinecraftProtocolTest {
    private final ProtocolProfile profile = ProtocolProfile.minecraft1710();

    @Test void waitsForSplitLengthPrefixAndPayloadThenEmitsZeroCopyFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new MinecraftFrameDecoder(profile));
        ByteBuf first = Unpooled.wrappedBuffer(new byte[] {(byte) 0x82});
        ByteBuf second = Unpooled.wrappedBuffer(new byte[] {0x01, 0x11});
        ByteBuf third = Unpooled.buffer(129).writeZero(129);
        try {
            assertFalse(channel.writeInbound(first));
            assertFalse(channel.writeInbound(second));
            assertTrue(channel.writeInbound(third));
            ByteBuf frame = channel.readInbound();
            assertEquals(130, frame.readableBytes());
            assertEquals(0x11, frame.getUnsignedByte(0));
            frame.release();
            assertNull(channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test void emitsEveryCoalescedFrameAndPreservesPayloadOwnership() {
        EmbeddedChannel channel = new EmbeddedChannel(new MinecraftFrameDecoder(profile));
        ByteBuf inbound = Unpooled.wrappedBuffer(new byte[] {2, 0, 1, 1, 0});
        try {
            assertTrue(channel.writeInbound(inbound));
            ByteBuf one = channel.readInbound();
            ByteBuf two = channel.readInbound();
            assertEquals(0, (int) one.readUnsignedByte());
            assertEquals(1, (int) one.readUnsignedByte());
            assertEquals(0, (int) two.readUnsignedByte());
            assertNull(channel.readInbound());
            one.release();
            two.release();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test void rejectsOversizedNegativeNonCanonicalAndOverlongFrameLengths() {
        assertBadFrame(new byte[] {3}, new ProtocolProfile(5, 2, 255, 16));
        assertBadFrame(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01}, profile);
        assertBadFrame(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F}, profile);
        assertBadFrame(new byte[] {(byte) 0x81, 0}, profile);
        assertBadFrame(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80}, profile);
    }

    @Test void roundTripsHandshakeAndLoginStartWithoutChangingInputReaderIndex() {
        MinecraftHandshake handshake = new MinecraftHandshake(5, "play.example", 25565, MinecraftHandshake.NextState.LOGIN);
        ByteBuf encodedHandshake = handshake.encode(UnpooledByteBufAllocator.DEFAULT, profile);
        try {
            int readerIndex = encodedHandshake.readerIndex();
            assertEquals(handshake, MinecraftHandshake.decode(encodedHandshake, profile));
            assertEquals(readerIndex, encodedHandshake.readerIndex());
        } finally { encodedHandshake.release(); }

        LoginStart login = new LoginStart("Layue_1");
        ByteBuf encodedLogin = login.encode(UnpooledByteBufAllocator.DEFAULT, profile);
        try { assertEquals(login, LoginStart.decode(encodedLogin, profile)); }
        finally { encodedLogin.release(); }

        MinecraftHandshake segmented = new MinecraftHandshake(5, "play.example\0extra", 25565,
                MinecraftHandshake.NextState.LOGIN);
        ByteBuf segmentedBody = segmented.encode(UnpooledByteBufAllocator.DEFAULT, profile);
        try { assertEquals(segmented, MinecraftHandshake.decode(segmentedBody, profile)); }
        finally { segmentedBody.release(); }
    }

    @Test void rejectsMalformedHandshakeAndOversizedLoginName() {
        ByteBuf trailing = Unpooled.wrappedBuffer(new byte[] {0, 5, 1, 'x', 0, 1, 2, 0});
        try { assertThrows(ProtocolException.class, () -> MinecraftHandshake.decode(trailing, profile)); }
        finally { trailing.release(); }

        ByteBuf tooLong = Unpooled.buffer();
        try {
            ProtocolVarInt.write(tooLong, 0);
            ProtocolStrings.write(tooLong, "12345678901234567", 32);
            assertThrows(ProtocolException.class, () -> LoginStart.decode(tooLong, profile));
        } finally { tooLong.release(); }
    }

    private static void assertBadFrame(byte[] bytes, ProtocolProfile profile) {
        EmbeddedChannel channel = new EmbeddedChannel(new MinecraftFrameDecoder(profile));
        ByteBuf input = Unpooled.wrappedBuffer(bytes);
        try {
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> channel.writeInbound(input));
        } finally {
            if (input.refCnt() > 0) input.release();
            channel.close();
        }
    }
}

package dev.strataproxy.infrastructure.minecraft;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftCustomPayloadBodyCodecTest {
    @Test
    void readsRemainingBytesBody() {
        var buffer = Unpooled.wrappedBuffer(bytes("abc"));

        var body = MinecraftCustomPayloadBodyCodec.readBody(
                buffer,
                MinecraftProtocolProfile.CustomPayloadLengthFormat.REMAINING_BYTES);

        assertEquals("abc", body.toString(StandardCharsets.UTF_8));
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void readsUnsignedShortBody() {
        var buffer = Unpooled.buffer();
        buffer.writeShort(3);
        buffer.writeBytes(bytes("abc"));
        buffer.writeByte(7);

        var body = MinecraftCustomPayloadBodyCodec.readBody(
                buffer,
                MinecraftProtocolProfile.CustomPayloadLengthFormat.UNSIGNED_SHORT);

        assertEquals("abc", body.toString(StandardCharsets.UTF_8));
        assertEquals(1, buffer.readableBytes());
        assertEquals(7, buffer.readUnsignedByte());
    }

    @Test
    void readsVarShortBody() {
        var buffer = Unpooled.buffer();
        MinecraftCustomPayloadBodyCodec.writeLength(
                buffer,
                MinecraftProtocolProfile.CustomPayloadLengthFormat.VARSHORT,
                0x8001);
        buffer.writeZero(0x8001);

        var body = MinecraftCustomPayloadBodyCodec.readBody(
                buffer,
                MinecraftProtocolProfile.CustomPayloadLengthFormat.VARSHORT);

        assertEquals(0x8001, body.readableBytes());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void rejectsOversizedUnsignedShortLength() {
        var buffer = Unpooled.buffer();

        assertThrows(IllegalArgumentException.class, () -> MinecraftCustomPayloadBodyCodec.writeLength(
                buffer,
                MinecraftProtocolProfile.CustomPayloadLengthFormat.UNSIGNED_SHORT,
                0x1_0000));
    }

    @Test
    void rejectsTruncatedLengthPrefixedBody() {
        var buffer = Unpooled.buffer();
        buffer.writeShort(4);
        buffer.writeBytes(bytes("abc"));

        assertThrows(IllegalArgumentException.class, () -> MinecraftCustomPayloadBodyCodec.readBody(
                buffer,
                MinecraftProtocolProfile.CustomPayloadLengthFormat.UNSIGNED_SHORT));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

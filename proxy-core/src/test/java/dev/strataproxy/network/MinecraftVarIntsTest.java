package dev.strataproxy.network;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftVarIntsTest {
    @Test
    void roundTripsRepresentativeValues() {
        var values = new int[] {0, 1, 127, 128, 255, 2_097_151, Integer.MAX_VALUE};
        for (var value : values) {
            var buffer = Unpooled.buffer();
            try {
                MinecraftVarInts.write(buffer, value);

                assertEquals(MinecraftVarInts.encodedSize(value), buffer.readableBytes());
                assertEquals(value, MinecraftVarInts.read(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void rejectsTooLongVarInt() {
        var buffer = Unpooled.wrappedBuffer(new byte[] {
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                0x00
        });
        try {
            assertThrows(MinecraftCodecException.class, () -> MinecraftVarInts.read(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void probesWithoutConsumingReaderIndex() {
        var buffer = Unpooled.buffer();
        try {
            MinecraftVarInts.write(buffer, 300);
            var readerIndex = buffer.readerIndex();

            var probe = MinecraftVarInts.probe(buffer);

            assertEquals(true, probe.complete());
            assertEquals(300, probe.value());
            assertEquals(2, probe.bytes());
            assertEquals(readerIndex, buffer.readerIndex());
        } finally {
            buffer.release();
        }
    }

    @Test
    void probeReportsIncompleteVarInt() {
        var buffer = Unpooled.wrappedBuffer(new byte[] {(byte) 0x80});
        try {
            var probe = MinecraftVarInts.probe(buffer);

            assertEquals(false, probe.complete());
            assertEquals(0, probe.bytes());
        } finally {
            buffer.release();
        }
    }
}

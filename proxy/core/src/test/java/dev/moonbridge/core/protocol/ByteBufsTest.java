package dev.moonbridge.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ByteBufsTest {
    @Test
    void fillReturnsTheFilledBufferStillOwnedByTheCaller() {
        ByteBuf buffer = Unpooled.buffer();
        ByteBuf result = ByteBufs.fill(buffer, out -> out.writeInt(7));
        assertSame(buffer, result);
        assertEquals(1, result.refCnt());
        assertEquals(7, result.readInt());
        result.release();
    }

    @Test
    void fillReleasesTheBufferAndRethrowsWhenTheWriterFails() {
        ByteBuf buffer = Unpooled.buffer();
        var failure = new IllegalStateException("boom");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ByteBufs.fill(buffer, out -> { out.writeInt(1); throw failure; })));
        assertEquals(0, buffer.refCnt());
    }

    @Test
    void fillAlsoReleasesOnErrors() {
        ByteBuf buffer = Unpooled.buffer();
        assertThrows(AssertionError.class, () -> ByteBufs.fill(buffer, out -> { throw new AssertionError("x"); }));
        assertEquals(0, buffer.refCnt());
    }

    @Test
    void useReleasesTheScratchBufferOnSuccessAndOnFailure() {
        ByteBuf ok = Unpooled.buffer();
        int answer = ByteBufs.use(ok, scratch -> 5);
        assertEquals(5, answer);
        assertEquals(0, ok.refCnt());

        ByteBuf failing = Unpooled.buffer();
        assertThrows(IllegalArgumentException.class,
                () -> ByteBufs.use(failing, scratch -> { throw new IllegalArgumentException(); }));
        assertEquals(0, failing.refCnt());
    }

    @Test
    void writeFramePrefixesTheLengthWithoutConsumingThePacket() {
        ByteBuf packet = Unpooled.buffer().writeBytes(new byte[] {1, 2, 3});
        ByteBuf output = Unpooled.buffer();
        ByteBufs.writeFrame(output, packet);
        assertEquals(3, packet.readableBytes());
        assertEquals(3, output.readByte());
        assertEquals(1, output.readByte());
        packet.release();
        output.release();
    }
}

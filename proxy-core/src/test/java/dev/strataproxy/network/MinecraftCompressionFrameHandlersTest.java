package dev.strataproxy.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftCompressionFrameHandlersTest {
    @Test
    void encoderAndDecoderRoundTripPacketPayloads() {
        var packet = Unpooled.copiedBuffer("packet-payload-".repeat(256), StandardCharsets.UTF_8);
        var encoder = new EmbeddedChannel(new MinecraftCompressionFrameEncoder(64));
        var decoder = new EmbeddedChannel(new MinecraftCompressionFrameDecoder(64, 16 * 1024, 16 * 1024));
        try {
            assertTrue(encoder.writeOutbound(packet.retainedDuplicate()));
            var frame = (ByteBuf) encoder.readOutbound();

            assertTrue(decoder.writeInbound(frame));
            var decoded = (ByteBuf) decoder.readInbound();
            try {
                assertArrayEquals(bytes(packet), bytes(decoded));
            } finally {
                decoded.release();
            }
        } finally {
            packet.release();
            assertFalse(encoder.finishAndReleaseAll());
            assertFalse(decoder.finishAndReleaseAll());
        }
    }

    @Test
    void decoderWaitsForCompleteFrame() {
        var packet = Unpooled.copiedBuffer("packet-payload-".repeat(32), StandardCharsets.UTF_8);
        var encoder = new EmbeddedChannel(new MinecraftCompressionFrameEncoder(16));
        var decoder = new EmbeddedChannel(new MinecraftCompressionFrameDecoder(16, 4096, 4096));
        try {
            assertTrue(encoder.writeOutbound(packet.retainedDuplicate()));
            var frame = (ByteBuf) encoder.readOutbound();
            var splitAt = Math.max(1, frame.readableBytes() / 2);
            var firstHalf = frame.readRetainedSlice(splitAt);
            var secondHalf = frame.readRetainedSlice(frame.readableBytes());
            frame.release();

            assertFalse(decoder.writeInbound(firstHalf));
            assertTrue(decoder.writeInbound(secondHalf));

            var decoded = (ByteBuf) decoder.readInbound();
            try {
                assertArrayEquals(bytes(packet), bytes(decoded));
            } finally {
                decoded.release();
            }
        } finally {
            packet.release();
            assertFalse(encoder.finishAndReleaseAll());
            assertFalse(decoder.finishAndReleaseAll());
        }
    }

    @Test
    void decoderRejectsFrameAboveMaximumLength() {
        var decoder = new EmbeddedChannel(new MinecraftCompressionFrameDecoder(16, 4, 4096));
        var frame = Unpooled.buffer();
        try {
            MinecraftVarInts.write(frame, 5);
            frame.writeZero(5);
            var exception = assertThrows(DecoderException.class, () -> {
                try {
                    decoder.writeInbound(frame);
                } finally {
                    decoder.finishAndReleaseAll();
                }
            });
            assertCodecCause(exception);
        } finally {
            // Ownership moves to EmbeddedChannel even when decode fails.
        }
    }

    @Test
    void decoderRejectsMalformedCompressedPayload() {
        var decoder = new EmbeddedChannel(new MinecraftCompressionFrameDecoder(16, 4096, 4096));
        var frame = Unpooled.buffer();
        try {
            MinecraftVarInts.write(frame, 5);
            MinecraftVarInts.write(frame, 128);
            frame.writeBytes(new byte[] {1, 2, 3});
            var exception = assertThrows(DecoderException.class, () -> {
                try {
                    decoder.writeInbound(frame);
                } finally {
                    decoder.finishAndReleaseAll();
                }
            });
            assertCodecCause(exception);
        } finally {
            // Ownership moves to EmbeddedChannel even when decode fails.
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        var bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static void assertCodecCause(Throwable exception) {
        var current = exception;
        while (current != null) {
            if (current instanceof MinecraftCodecException) {
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected MinecraftCodecException in cause chain", exception);
    }

}

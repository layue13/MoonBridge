package dev.strataproxy.codec.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftZstdCompressionCodecTest {
    @Test
    void encodesAndDecodesUncompressedFrameBelowThreshold() {
        var codec = new MinecraftZstdCompressionCodec();
        var packet = copiedBuffer("small-packet");
        ByteBuf frame = null;
        ByteBuf decoded = null;
        try {
            frame = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, packet, 128);
            var packetLength = MinecraftVarInts.read(frame.slice());
            assertEquals(packet.readableBytes() + 1, packetLength);

            decoded = codec.decodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, frame, 128, 1024);
            assertArrayEquals(toBytes(packet), toBytes(decoded));
        } finally {
            release(packet);
            release(frame);
            release(decoded);
        }
    }

    @Test
    void encodesAndDecodesCompressedFrameWithDictionary() {
        var dictionary = trainDictionary();
        var codec = new MinecraftZstdCompressionCodec(1, dictionary);
        var packet = copiedBuffer("chunk-palette:stone,dirt,grass;nbt:".repeat(256));
        ByteBuf frame = null;
        ByteBuf decoded = null;
        try {
            frame = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, packet, 64);
            var frameView = frame.slice();
            MinecraftVarInts.read(frameView);
            assertEquals(packet.readableBytes(), MinecraftVarInts.read(frameView));
            assertTrue(frameView.readableBytes() < packet.readableBytes());

            decoded = codec.decodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, frame, 64, 16 * 1024);
            assertArrayEquals(toBytes(packet), toBytes(decoded));
        } finally {
            release(packet);
            release(frame);
            release(decoded);
        }
    }

    @Test
    void rejectsWrongDictionary() {
        var codec = new MinecraftZstdCompressionCodec(1, trainDictionary());
        var wrongDictionaryCodec = new MinecraftZstdCompressionCodec(1, copiedBytes("other samples ".repeat(256)));
        var packet = copiedBuffer("chunk-palette:stone,dirt,grass;nbt:".repeat(256));
        ByteBuf frame = null;
        try {
            frame = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, packet, 64);
            var compressedFrame = frame;

            assertThrows(MinecraftCodecException.class,
                    () -> wrongDictionaryCodec.decodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, compressedFrame, 64, 16 * 1024));
        } finally {
            release(packet);
            release(frame);
        }
    }

    private static byte[] trainDictionary() {
        var samples = new ArrayList<byte[]>();
        for (var i = 0; i < 64; i++) {
            samples.add(copiedBytes("chunk-palette:stone,dirt,grass;nbt:{x:" + i + ",section:" + (i % 16) + "}"));
        }
        return MinecraftZstdDictionaryTrainer.train(samples, 1024, 1);
    }

    private static ByteBuf copiedBuffer(String value) {
        return Unpooled.copiedBuffer(value, StandardCharsets.UTF_8);
    }

    private static byte[] copiedBytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] toBytes(ByteBuf buffer) {
        var bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static void release(ByteBuf buffer) {
        if (buffer != null) {
            buffer.release();
        }
    }

    private static final class UnpooledByteBufAllocatorHolder {
        private static final io.netty.buffer.ByteBufAllocator ALLOCATOR = io.netty.buffer.UnpooledByteBufAllocator.DEFAULT;
    }
}

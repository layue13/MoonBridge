package dev.strataproxy.infrastructure.minecraft.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftCompressionCodecTest {
    @Test
    void encodesAndDecodesUncompressedFrameBelowThreshold() {
        try (var codec = new MinecraftCompressionCodec()) {
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
    }

    @Test
    void encodesAndDecodesCompressedFrameAtThreshold() {
        try (var codec = new MinecraftCompressionCodec()) {
            var packet = copiedBuffer("payload-".repeat(512));
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
    }

    @Test
    void rejectsUncompressedPacketThatExceedsThreshold() {
        try (var codec = new MinecraftCompressionCodec()) {
            var oversized = copiedBuffer("0123456789");
            var frame = Unpooled.buffer();
            try {
                MinecraftVarInts.write(frame, oversized.readableBytes() + 1);
                MinecraftVarInts.write(frame, 0);
                frame.writeBytes(oversized, oversized.readerIndex(), oversized.readableBytes());

                assertThrows(MinecraftCodecException.class,
                        () -> codec.decodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, frame, 4, 1024));
            } finally {
                release(oversized);
                release(frame);
            }
        }
    }

    @Test
    void rejectsCompressedPacketUnderThreshold() {
        try (var codec = new MinecraftCompressionCodec()) {
            var frame = Unpooled.buffer();
            try {
                MinecraftVarInts.write(frame, 2);
                MinecraftVarInts.write(frame, 3);
                frame.writeByte(0);

                assertThrows(MinecraftCodecException.class,
                        () -> codec.decodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, frame, 4, 1024));
            } finally {
                release(frame);
            }
        }
    }

    @Test
    void rejectsCompressedPacketAboveMaximumUncompressedSize() {
        try (var codec = new MinecraftCompressionCodec()) {
            var packet = copiedBuffer("payload-".repeat(128));
            ByteBuf frame = null;
            try {
                frame = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, packet, 1);
                var compressedFrame = frame;

                assertThrows(MinecraftCodecException.class,
                        () -> codec.decodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, compressedFrame, 1, 32));
            } finally {
                release(packet);
                release(frame);
            }
        }
    }

    @Test
    void rejectsMalformedCompressedPayload() {
        try (var codec = new MinecraftCompressionCodec()) {
            var frame = Unpooled.buffer();
            try {
                MinecraftVarInts.write(frame, 5);
                MinecraftVarInts.write(frame, 128);
                frame.writeBytes(new byte[] {1, 2, 3});

                assertThrows(MinecraftCodecException.class,
                        () -> codec.decodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, frame, 64, 1024));
            } finally {
                release(frame);
            }
        }
    }

    private static ByteBuf copiedBuffer(String value) {
        return Unpooled.copiedBuffer(value, StandardCharsets.UTF_8);
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

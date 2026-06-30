package dev.strataproxy.codec.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftCompressedFrameRewriterTest {
    @Test
    void rewritesCompressedFrameToUncompressedWhenTargetThresholdIsHigher() {
        try (var codec = new MinecraftCompressionCodec();
             var rewriter = new MinecraftCompressedFrameRewriter(new MinecraftCompressionCodec())) {
            var packet = copiedBuffer("login-registry-payload-".repeat(64));
            ByteBuf source = null;
            ByteBuf decoded = null;
            try {
                source = codec.encodeFrame(ALLOCATOR, packet, 32);
                try (var result = rewriter.rewrite(ALLOCATOR, source, 32, packet.readableBytes() + 1, 64 * 1024)) {
                    assertTrue(result.wasCompressed());
                    assertFalse(result.isCompressed());
                    assertEquals(packet.readableBytes(), result.originalUncompressedBytes());
                    assertEquals(packet.readableBytes(), result.rewrittenUncompressedBytes());

                    decoded = codec.decodeFrame(ALLOCATOR, result.frame(), packet.readableBytes() + 1, 64 * 1024);
                    assertArrayEquals(toBytes(packet), toBytes(decoded));
                }
            } finally {
                release(packet);
                release(source);
                release(decoded);
            }
        }
    }

    @Test
    void rewritesUncompressedFrameToCompressedWhenTargetThresholdIsLower() {
        try (var codec = new MinecraftCompressionCodec();
             var rewriter = new MinecraftCompressedFrameRewriter(new MinecraftCompressionCodec())) {
            var packet = copiedBuffer("mod-sync-smallish-payload-".repeat(32));
            ByteBuf source = null;
            ByteBuf decoded = null;
            try {
                source = codec.encodeFrame(ALLOCATOR, packet, packet.readableBytes() + 1);
                try (var result = rewriter.rewrite(ALLOCATOR, source, packet.readableBytes() + 1, 1, 64 * 1024)) {
                    assertFalse(result.wasCompressed());
                    assertTrue(result.isCompressed());
                    assertTrue(result.rewrittenCompressedPayloadBytes() < packet.readableBytes());

                    decoded = codec.decodeFrame(ALLOCATOR, result.frame(), 1, 64 * 1024);
                    assertArrayEquals(toBytes(packet), toBytes(decoded));
                }
            } finally {
                release(packet);
                release(source);
                release(decoded);
            }
        }
    }

    @Test
    void doesNotMoveSourceReaderIndex() {
        try (var codec = new MinecraftCompressionCodec();
             var rewriter = new MinecraftCompressedFrameRewriter(new MinecraftCompressionCodec())) {
            var packet = copiedBuffer("payload-".repeat(16));
            ByteBuf source = null;
            try {
                source = codec.encodeFrame(ALLOCATOR, packet, 16);
                var readerIndex = source.readerIndex();
                try (var ignored = rewriter.rewrite(ALLOCATOR, source, 16, 128, 64 * 1024)) {
                    assertEquals(readerIndex, source.readerIndex());
                }
            } finally {
                release(packet);
                release(source);
            }
        }
    }

    @Test
    void rejectsBuffersWithMoreThanOneFrame() {
        try (var codec = new MinecraftCompressionCodec();
             var rewriter = new MinecraftCompressedFrameRewriter(new MinecraftCompressionCodec())) {
            var packet = copiedBuffer("payload-".repeat(16));
            ByteBuf first = null;
            ByteBuf second = null;
            ByteBuf combined = null;
            try {
                first = codec.encodeFrame(ALLOCATOR, packet, 16);
                second = codec.encodeFrame(ALLOCATOR, packet, 16);
                combined = Unpooled.buffer(first.readableBytes() + second.readableBytes());
                combined.writeBytes(first, first.readerIndex(), first.readableBytes());
                combined.writeBytes(second, second.readerIndex(), second.readableBytes());

                var input = combined;
                assertThrows(MinecraftCodecException.class,
                        () -> rewriter.rewrite(ALLOCATOR, input, 16, 128, 64 * 1024));
            } finally {
                release(packet);
                release(first);
                release(second);
                release(combined);
            }
        }
    }

    @Test
    void rewritesBatchWithMoreThanOneFrame() {
        try (var codec = new MinecraftCompressionCodec();
             var rewriter = new MinecraftCompressedFrameRewriter(new MinecraftCompressionCodec())) {
            var firstPacket = copiedBuffer("first-payload-".repeat(32));
            var secondPacket = copiedBuffer("second-payload-".repeat(32));
            ByteBuf first = null;
            ByteBuf second = null;
            ByteBuf combined = null;
            ByteBuf firstDecoded = null;
            ByteBuf secondDecoded = null;
            try {
                first = codec.encodeFrame(ALLOCATOR, firstPacket, 16);
                second = codec.encodeFrame(ALLOCATOR, secondPacket, 16);
                combined = Unpooled.buffer(first.readableBytes() + second.readableBytes());
                combined.writeBytes(first, first.readerIndex(), first.readableBytes());
                combined.writeBytes(second, second.readerIndex(), second.readableBytes());

                try (var result = rewriter.rewriteBatch(ALLOCATOR, combined, 16, 4096, 64 * 1024, 8)) {
                    assertEquals(2, result.frameCount());
                    assertTrue(result.wasCompressed());
                    assertFalse(result.isCompressed());

                    var view = result.frames().slice();
                    firstDecoded = codec.decodeFrame(ALLOCATOR, view, 4096, 64 * 1024);
                    secondDecoded = codec.decodeFrame(ALLOCATOR, view, 4096, 64 * 1024);
                    assertEquals(0, view.readableBytes());
                    assertArrayEquals(toBytes(firstPacket), toBytes(firstDecoded));
                    assertArrayEquals(toBytes(secondPacket), toBytes(secondDecoded));
                }
            } finally {
                release(firstPacket);
                release(secondPacket);
                release(first);
                release(second);
                release(combined);
                release(firstDecoded);
                release(secondDecoded);
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

    private static final io.netty.buffer.ByteBufAllocator ALLOCATOR = io.netty.buffer.UnpooledByteBufAllocator.DEFAULT;
}

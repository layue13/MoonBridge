package dev.strataproxy.codec.minecraft;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.Arrays;
import java.util.Objects;

public final class MinecraftZstdCompressionCodec {
    private static final int DEFAULT_LEVEL = 1;

    private final int level;
    private final byte[] dictionary;

    public MinecraftZstdCompressionCodec() {
        this(DEFAULT_LEVEL, null);
    }

    public MinecraftZstdCompressionCodec(int level, byte[] dictionary) {
        this.level = level;
        this.dictionary = dictionary == null || dictionary.length == 0 ? null : Arrays.copyOf(dictionary, dictionary.length);
    }

    public ByteBuf encodeFrame(ByteBufAllocator allocator, ByteBuf packet, int threshold) {
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(packet, "packet");
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must be non-negative");
        }
        var packetSize = packet.readableBytes();
        if (packetSize < threshold) {
            return encodeUncompressedFrame(allocator, packet);
        }
        return encodeCompressedFrame(allocator, packet, packetSize);
    }

    public ByteBuf decodeFrame(ByteBufAllocator allocator, ByteBuf frame, int threshold, int maxUncompressedBytes) {
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(frame, "frame");
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must be non-negative");
        }
        if (maxUncompressedBytes <= 0) {
            throw new IllegalArgumentException("maxUncompressedBytes must be positive");
        }
        var packetLength = MinecraftVarInts.read(frame);
        if (packetLength < 0 || packetLength > frame.readableBytes()) {
            throw new MinecraftCodecException("invalid zstd packet frame length");
        }
        var packetFrame = frame.readRetainedSlice(packetLength);
        try {
            var dataLength = MinecraftVarInts.read(packetFrame);
            if (dataLength == 0) {
                if (threshold > 0 && packetFrame.readableBytes() >= threshold) {
                    throw new MinecraftCodecException("uncompressed zstd packet exceeds compression threshold");
                }
                return packetFrame.readRetainedSlice(packetFrame.readableBytes());
            }
            if (dataLength < threshold) {
                throw new MinecraftCodecException("zstd packet is smaller than compression threshold");
            }
            if (dataLength > maxUncompressedBytes) {
                throw new MinecraftCodecException("zstd packet exceeds maximum uncompressed size");
            }
            return decompress(allocator, packetFrame, dataLength);
        } finally {
            packetFrame.release();
        }
    }

    private ByteBuf encodeUncompressedFrame(ByteBufAllocator allocator, ByteBuf packet) {
        var packetSize = packet.readableBytes();
        var length = MinecraftVarInts.encodedSize(0) + packetSize;
        var output = allocator.buffer(MinecraftVarInts.encodedSize(length) + length);
        MinecraftVarInts.write(output, length);
        MinecraftVarInts.write(output, 0);
        output.writeBytes(packet, packet.readerIndex(), packetSize);
        return output;
    }

    private ByteBuf encodeCompressedFrame(ByteBufAllocator allocator, ByteBuf packet, int packetSize) {
        var source = toBytes(packet);
        var maxCompressedBytes = Zstd.compressBound(packetSize);
        if (maxCompressedBytes > Integer.MAX_VALUE) {
            throw new MinecraftCodecException("zstd compressed packet bound exceeds maximum integer size");
        }
        var compressed = new byte[(int) maxCompressedBytes];
        var compressedBytes = dictionary == null
                ? Zstd.compress(compressed, source, level)
                : Zstd.compress(compressed, source, dictionary, level);
        if (Zstd.isError(compressedBytes)) {
            throw new MinecraftCodecException("zstd compression failed: " + Zstd.getErrorName(compressedBytes));
        }
        var compressedSize = Math.toIntExact(compressedBytes);
        var length = MinecraftVarInts.encodedSize(packetSize) + compressedSize;
        var output = allocator.buffer(MinecraftVarInts.encodedSize(length) + length);
        MinecraftVarInts.write(output, length);
        MinecraftVarInts.write(output, packetSize);
        output.writeBytes(compressed, 0, compressedSize);
        return output;
    }

    private ByteBuf decompress(ByteBufAllocator allocator, ByteBuf compressedFrame, int dataLength) {
        var compressed = toBytes(compressedFrame);
        var decompressed = new byte[dataLength];
        var decompressedBytes = dictionary == null
                ? Zstd.decompress(decompressed, compressed)
                : Zstd.decompressUsingDict(decompressed, 0, compressed, 0, compressed.length, dictionary);
        if (Zstd.isError(decompressedBytes)) {
            throw new MinecraftCodecException("zstd decompression failed: " + Zstd.getErrorName(decompressedBytes));
        }
        if (decompressedBytes != dataLength) {
            throw new MinecraftCodecException("zstd packet size does not match declaration");
        }
        var output = allocator.buffer(dataLength, dataLength);
        output.writeBytes(decompressed);
        return output;
    }

    private static byte[] toBytes(ByteBuf input) {
        var bytes = new byte[input.readableBytes()];
        input.getBytes(input.readerIndex(), bytes);
        return bytes;
    }
}

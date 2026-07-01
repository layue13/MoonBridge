package dev.strataproxy.codec.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Codec for Minecraft's zlib-compressed packet frame format.
 *
 * <p>Returned {@link ByteBuf} instances are newly allocated and must be released by the caller.</p>
 */
public final class MinecraftCompressionCodec implements AutoCloseable {
    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private final Deflater deflater;
    private final Inflater inflater;
    private final byte[] buffer;

    /**
     * Creates a codec using the JDK default compression level and scratch-buffer size.
     */
    public MinecraftCompressionCodec() {
        this(Deflater.DEFAULT_COMPRESSION, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Creates a codec with explicit compression settings.
     *
     * @param compressionLevel JDK {@link Deflater} compression level
     * @param scratchBufferBytes temporary buffer size used while compressing or inflating
     */
    public MinecraftCompressionCodec(int compressionLevel, int scratchBufferBytes) {
        if (scratchBufferBytes <= 0) {
            throw new IllegalArgumentException("scratchBufferBytes must be positive");
        }
        this.deflater = new Deflater(compressionLevel);
        this.inflater = new Inflater();
        this.buffer = new byte[scratchBufferBytes];
    }

    /**
     * Encodes a packet payload into one Minecraft compressed-frame envelope.
     *
     * @param allocator allocator for the returned buffer
     * @param packet unframed packet payload; reader index is not advanced
     * @param threshold compression threshold in bytes
     * @return newly allocated frame buffer owned by the caller
     */
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

    /**
     * Decodes one complete Minecraft compressed-frame envelope into a packet payload.
     *
     * @param allocator allocator for the returned buffer
     * @param frame complete compressed-frame envelope; reader index is advanced
     * @param threshold negotiated compression threshold
     * @param maxUncompressedBytes safety limit for decompressed payload size
     * @return newly allocated packet payload owned by the caller
     */
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
            throw new MinecraftCodecException("invalid compressed packet frame length");
        }
        var packetFrame = frame.readRetainedSlice(packetLength);
        try {
            var dataLength = MinecraftVarInts.read(packetFrame);
            if (dataLength == 0) {
                if (threshold > 0 && packetFrame.readableBytes() >= threshold) {
                    throw new MinecraftCodecException("uncompressed packet exceeds compression threshold");
                }
                return packetFrame.readRetainedSlice(packetFrame.readableBytes());
            }
            if (dataLength < threshold) {
                throw new MinecraftCodecException("compressed packet is smaller than compression threshold");
            }
            if (dataLength > maxUncompressedBytes) {
                throw new MinecraftCodecException("compressed packet exceeds maximum uncompressed size");
            }
            return inflate(allocator, packetFrame, dataLength);
        } finally {
            packetFrame.release();
        }
    }

    /**
     * Releases the underlying deflater and inflater.
     */
    @Override
    public void close() {
        deflater.end();
        inflater.end();
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
        var compressed = allocator.buffer(Math.max(64, packetSize / 2));
        try {
            deflater.reset();
            deflater.setInput(toBytes(packet));
            deflater.finish();
            while (!deflater.finished()) {
                var bytes = deflater.deflate(buffer);
                compressed.writeBytes(buffer, 0, bytes);
            }
            var length = MinecraftVarInts.encodedSize(packetSize) + compressed.readableBytes();
            var output = allocator.buffer(MinecraftVarInts.encodedSize(length) + length);
            MinecraftVarInts.write(output, length);
            MinecraftVarInts.write(output, packetSize);
            output.writeBytes(compressed, compressed.readerIndex(), compressed.readableBytes());
            return output;
        } finally {
            compressed.release();
        }
    }

    private ByteBuf inflate(ByteBufAllocator allocator, ByteBuf compressedFrame, int dataLength) {
        var output = allocator.buffer(dataLength, dataLength);
        try {
            inflater.reset();
            inflater.setInput(toBytes(compressedFrame));
            while (!inflater.finished()) {
                var bytes = inflater.inflate(buffer);
                if (bytes == 0) {
                    if (inflater.needsInput()) {
                        throw new MinecraftCodecException("truncated compressed packet");
                    }
                    if (inflater.needsDictionary()) {
                        throw new MinecraftCodecException("compressed packet requires a dictionary");
                    }
                    throw new MinecraftCodecException("compressed packet made no progress");
                }
                if (output.readableBytes() + bytes > dataLength) {
                    throw new MinecraftCodecException("compressed packet inflated beyond declared size");
                }
                output.writeBytes(buffer, 0, bytes);
            }
            if (output.readableBytes() != dataLength) {
                throw new MinecraftCodecException("compressed packet size does not match declaration");
            }
            return output;
        } catch (DataFormatException exception) {
            output.release();
            throw new MinecraftCodecException("malformed compressed packet", exception);
        } catch (RuntimeException exception) {
            output.release();
            throw exception;
        }
    }

    private static byte[] toBytes(ByteBuf input) {
        var bytes = new byte[input.readableBytes()];
        input.getBytes(input.readerIndex(), bytes);
        return bytes;
    }
}

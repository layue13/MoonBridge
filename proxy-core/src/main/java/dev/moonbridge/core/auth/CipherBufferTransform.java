package dev.moonbridge.core.auth;

import dev.moonbridge.core.protocol.ByteBufs;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;
import java.util.Arrays;

/** Applies one continuous Minecraft CFB8 cipher to a buffer without changing its read index. */
final class CipherBufferTransform {
    private CipherBufferTransform() { }

    static ByteBuf update(ByteBufAllocator allocator, Cipher cipher, ByteBuf input) {
        int length = input.readableBytes();
        boolean direct = input.isDirect() && !input.isReadOnly() && input.nioBufferCount() == 1;
        ByteBuf output = direct ? allocator.directBuffer(length, length) : allocator.buffer(length, length);
        try {
            return ByteBufs.fill(output, target -> transform(cipher, input, target, length, direct));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Minecraft CFB8 transformation failed", failure);
        }
    }

    private static void transform(Cipher cipher, ByteBuf input, ByteBuf output, int length, boolean direct) {
        try {
            if (direct && output.nioBufferCount() == 1) {
                int written = cipher.update(input.internalNioBuffer(input.readerIndex(), length),
                        output.internalNioBuffer(0, length));
                if (written != length) throw new IllegalStateException("CFB8 output length changed");
                output.writerIndex(written);
            } else {
                byte[] source = new byte[length];
                byte[] transformed = null;
                try {
                    input.getBytes(input.readerIndex(), source);
                    transformed = cipher.update(source);
                    if (transformed == null || transformed.length != length) {
                        throw new IllegalStateException("CFB8 output length changed");
                    }
                    output.writeBytes(transformed);
                } finally {
                    Arrays.fill(source, (byte) 0);
                    if (transformed != null) Arrays.fill(transformed, (byte) 0);
                }
            }
        } catch (ShortBufferException failure) {
            throw new IllegalStateException("CFB8 output buffer was too small", failure);
        }
    }
}

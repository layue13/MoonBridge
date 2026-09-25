package dev.strataproxy.core.auth;

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
            return output;
        } catch (ShortBufferException | RuntimeException failure) {
            output.release();
            throw new IllegalStateException("Minecraft CFB8 transformation failed", failure);
        } catch (Error failure) {
            output.release();
            throw failure;
        }
    }
}

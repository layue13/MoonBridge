package dev.strataproxy.codec.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import javax.crypto.Cipher;
import java.util.List;

public final class MinecraftCipherDecoder extends ByteToMessageDecoder {
    private final Cipher cipher;

    public MinecraftCipherDecoder(byte[] sharedSecret) {
        this(MinecraftEncryption.newDecryptCipher(sharedSecret));
    }

    public MinecraftCipherDecoder(Cipher cipher) {
        if (cipher == null) {
            throw new IllegalArgumentException("cipher must not be null");
        }
        this.cipher = cipher;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        if (!input.isReadable()) {
            return;
        }
        var encrypted = new byte[input.readableBytes()];
        input.readBytes(encrypted);
        var decrypted = cipher.update(encrypted);
        if (decrypted != null && decrypted.length > 0) {
            output.add(context.alloc().buffer(decrypted.length).writeBytes(decrypted));
        }
    }
}

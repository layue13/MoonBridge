package dev.strataproxy.codec.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import javax.crypto.Cipher;

public final class MinecraftCipherEncoder extends MessageToByteEncoder<ByteBuf> {
    private final Cipher cipher;

    public MinecraftCipherEncoder(byte[] sharedSecret) {
        this(MinecraftEncryption.newEncryptCipher(sharedSecret));
    }

    public MinecraftCipherEncoder(Cipher cipher) {
        if (cipher == null) {
            throw new IllegalArgumentException("cipher must not be null");
        }
        this.cipher = cipher;
    }

    @Override
    protected void encode(ChannelHandlerContext context, ByteBuf input, ByteBuf output) {
        var plaintext = new byte[input.readableBytes()];
        input.readBytes(plaintext);
        var encrypted = cipher.update(plaintext);
        if (encrypted != null && encrypted.length > 0) {
            output.writeBytes(encrypted);
        }
    }
}

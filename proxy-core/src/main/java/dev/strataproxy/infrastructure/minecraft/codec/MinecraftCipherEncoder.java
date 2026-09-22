package dev.strataproxy.infrastructure.minecraft.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import javax.crypto.Cipher;

/**
 * Netty encoder that encrypts Minecraft AES/CFB8 traffic after login encryption is enabled.
 */
public final class MinecraftCipherEncoder extends MessageToByteEncoder<ByteBuf> {
    private final Cipher cipher;

    /**
 * Documents this public API element.
 *
     * @param sharedSecret 16-byte Minecraft shared secret
     */
    public MinecraftCipherEncoder(byte[] sharedSecret) {
        this(MinecraftEncryption.newEncryptCipher(sharedSecret));
    }

    /**
 * Documents this public API element.
 *
     * @param cipher initialized encrypt cipher
     */
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

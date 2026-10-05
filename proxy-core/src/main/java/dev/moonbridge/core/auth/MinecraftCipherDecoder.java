package dev.moonbridge.core.auth;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decrypts the raw Minecraft TCP byte stream with the protocol's continuous AES/CFB8 state.
 * Place before the frame decoder so both frame lengths and payloads are decrypted.
 */
public final class MinecraftCipherDecoder extends MessageToMessageDecoder<ByteBuf> {
    private final Cipher cipher;

    public MinecraftCipherDecoder(byte[] sharedSecret) {
        this.cipher = createCipher(sharedSecret);
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        output.add(CipherBufferTransform.update(context.alloc(), cipher, input));
    }

    private static Cipher createCipher(byte[] sharedSecret) {
        if (sharedSecret == null || sharedSecret.length != AuthenticatedEncryption.SHARED_SECRET_BYTES) {
            throw new IllegalArgumentException("AES shared secret must contain exactly 16 bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
            SecretKeySpec key = new SecretKeySpec(sharedSecret.clone(), "AES");
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(sharedSecret));
            return cipher;
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("AES/CFB8 is required by the Java platform", failure);
        }
    }
}

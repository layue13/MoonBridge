package dev.moonbridge.core.auth;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts the raw Minecraft TCP byte stream with continuous AES/CFB8 state.
 * Place before the frame encoder in pipeline order; outbound traversal encrypts framed bytes.
 */
public final class MinecraftCipherEncoder extends MessageToMessageEncoder<ByteBuf> {
    private final Cipher cipher;

    public MinecraftCipherEncoder(byte[] sharedSecret) {
        if (sharedSecret == null || sharedSecret.length != AuthenticatedEncryption.SHARED_SECRET_BYTES) {
            throw new IllegalArgumentException("AES shared secret must contain exactly 16 bytes");
        }
        try {
            SecretKeySpec key = new SecretKeySpec(sharedSecret.clone(), "AES");
            cipher = Cipher.getInstance("AES/CFB8/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(sharedSecret));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("AES/CFB8 is required by the Java platform", failure);
        }
    }

    @Override
    protected void encode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        output.add(CipherBufferTransform.update(context.alloc(), cipher, input));
    }
}

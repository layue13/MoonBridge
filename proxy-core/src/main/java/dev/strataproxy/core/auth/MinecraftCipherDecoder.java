package dev.strataproxy.core.auth;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.util.ReferenceCountUtil;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decrypts the raw Minecraft TCP byte stream with the protocol's continuous AES/CFB8 state.
 * Place before the frame decoder so both frame lengths and payloads are decrypted.
 */
public final class MinecraftCipherDecoder extends ChannelInboundHandlerAdapter {
    private final Cipher cipher;

    public MinecraftCipherDecoder(byte[] sharedSecret) {
        this.cipher = createCipher(sharedSecret);
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!(message instanceof ByteBuf input)) {
            context.fireChannelRead(message);
            return;
        }
        byte[] source = new byte[input.readableBytes()];
        byte[] decoded = null;
        ByteBuf output = null;
        try {
            input.getBytes(input.readerIndex(), source);
            decoded = cipher.update(source);
            if (decoded == null) decoded = new byte[0];
            output = context.alloc().buffer(decoded.length);
            output.writeBytes(decoded);
        } catch (RuntimeException failure) {
            if (output != null) output.release();
            throw new DecoderException("Minecraft AES/CFB8 decryption failed", failure);
        } finally {
            ReferenceCountUtil.release(input);
            Arrays.fill(source, (byte) 0);
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
        }
        context.fireChannelRead(output);
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

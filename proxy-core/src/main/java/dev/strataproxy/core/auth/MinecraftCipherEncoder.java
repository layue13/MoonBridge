package dev.strataproxy.core.auth;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts the raw Minecraft TCP byte stream with continuous AES/CFB8 state.
 * Place before the frame encoder in pipeline order; outbound traversal encrypts framed bytes.
 */
public final class MinecraftCipherEncoder extends ChannelOutboundHandlerAdapter {
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
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        if (!(message instanceof ByteBuf input)) {
            context.write(message, promise);
            return;
        }
        byte[] source = new byte[input.readableBytes()];
        byte[] encrypted = null;
        ByteBuf output = null;
        try {
            input.getBytes(input.readerIndex(), source);
            encrypted = cipher.update(source);
            if (encrypted == null) encrypted = new byte[0];
            output = context.alloc().buffer(encrypted.length);
            output.writeBytes(encrypted);
        } catch (RuntimeException failure) {
            promise.setFailure(failure);
            if (output != null) output.release();
            ReferenceCountUtil.release(input);
            Arrays.fill(source, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            return;
        }
        ReferenceCountUtil.release(input);
        Arrays.fill(source, (byte) 0);
        if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        try {
            context.write(output, promise);
        } catch (RuntimeException failure) {
            ReferenceCountUtil.release(output);
            promise.setFailure(failure);
        }
    }
}

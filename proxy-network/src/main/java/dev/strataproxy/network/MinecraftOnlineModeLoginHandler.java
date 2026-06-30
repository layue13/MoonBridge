package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCipherDecoder;
import dev.strataproxy.codec.minecraft.MinecraftCipherEncoder;
import dev.strataproxy.codec.minecraft.MinecraftEncryption;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.List;

final class MinecraftOnlineModeLoginHandler extends ByteToMessageDecoder {
    private static final int LOGIN_START_PACKET_ID = 0x00;
    private static final int ENCRYPTION_REQUEST_PACKET_ID = 0x01;
    private static final int ENCRYPTION_RESPONSE_PACKET_ID = 0x01;
    private static final String CIPHER_DECODER = "minecraft-cipher-decoder";
    private static final String CIPHER_ENCODER = "minecraft-cipher-encoder";

    private final int maxFrameBytes;
    private final KeyPair keyPair;
    private final byte[] verifyToken;
    private final AuthenticatedLoginCallback callback;
    private ByteBuf loginStartFrame;
    private String username = "";
    private boolean waitingForEncryptionResponse;

    MinecraftOnlineModeLoginHandler(
            int maxFrameBytes,
            KeyPair keyPair,
            byte[] verifyToken,
            AuthenticatedLoginCallback callback) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        if (keyPair == null) {
            throw new IllegalArgumentException("keyPair must not be null");
        }
        if (verifyToken == null || verifyToken.length == 0) {
            throw new IllegalArgumentException("verifyToken must not be empty");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.keyPair = keyPair;
        this.verifyToken = verifyToken.clone();
        this.callback = callback;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        var probe = MinecraftProtocolCodec.probeFrame(input, maxFrameBytes);
        if (!probe.complete()) {
            return;
        }
        var frame = input.readRetainedSlice(probe.totalBytes());
        if (!waitingForEncryptionResponse) {
            handleLoginStart(context, frame, probe);
        } else {
            handleEncryptionResponse(context, frame, probe);
        }
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext context) {
        if (loginStartFrame != null) {
            loginStartFrame.release();
            loginStartFrame = null;
        }
    }

    private void handleLoginStart(ChannelHandlerContext context, ByteBuf frame, MinecraftProtocolCodec.FrameProbe probe) {
        try {
            var payload = frame.retainedDuplicate();
            try {
                payload.skipBytes(probe.varIntBytes());
                var packetId = MinecraftProtocolCodec.readVarInt(payload);
                if (packetId != LOGIN_START_PACKET_ID) {
                    throw new IllegalArgumentException("expected Login Start packet, got " + packetId);
                }
                username = MinecraftProtocolCodec.readString(payload, 16);
            } finally {
                payload.release();
            }
            loginStartFrame = frame;
            waitingForEncryptionResponse = true;
            context.writeAndFlush(encryptionRequestFrame());
        } catch (RuntimeException exception) {
            frame.release();
            context.fireExceptionCaught(exception);
            context.close();
        }
    }

    private void handleEncryptionResponse(ChannelHandlerContext context, ByteBuf frame, MinecraftProtocolCodec.FrameProbe probe) {
        try {
            var payload = frame.retainedDuplicate();
            byte[] sharedSecret;
            byte[] returnedVerifyToken;
            try {
                payload.skipBytes(probe.varIntBytes());
                var packetId = MinecraftProtocolCodec.readVarInt(payload);
                if (packetId != ENCRYPTION_RESPONSE_PACKET_ID) {
                    throw new IllegalArgumentException("expected Encryption Response packet, got " + packetId);
                }
                sharedSecret = MinecraftEncryption.decryptSharedSecret(keyPair.getPrivate(), readByteArray(payload, 512));
                returnedVerifyToken = MinecraftEncryption.decryptVerifyToken(keyPair.getPrivate(), readByteArray(payload, 512));
            } finally {
                payload.release();
            }
            if (!Arrays.equals(verifyToken, returnedVerifyToken)) {
                throw new IllegalArgumentException("Minecraft encryption verify token mismatch");
            }
            installCiphers(context, sharedSecret);
            var authenticatedLoginStart = loginStartFrame;
            loginStartFrame = null;
            callback.authenticated(context, authenticatedLoginStart, sharedSecret, username);
            context.pipeline().remove(this);
        } catch (RuntimeException exception) {
            context.fireExceptionCaught(exception);
            context.close();
        } finally {
            frame.release();
        }
    }

    private void installCiphers(ChannelHandlerContext context, byte[] sharedSecret) {
        var pipeline = context.pipeline();
        if (pipeline.get(CIPHER_DECODER) == null) {
            pipeline.addBefore(context.name(), CIPHER_DECODER, new MinecraftCipherDecoder(sharedSecret));
        }
        if (pipeline.get(CIPHER_ENCODER) == null) {
            pipeline.addFirst(CIPHER_ENCODER, new MinecraftCipherEncoder(sharedSecret));
        }
    }

    private ByteBuf encryptionRequestFrame() {
        var payload = Unpooled.buffer();
        MinecraftVarInts.write(payload, ENCRYPTION_REQUEST_PACKET_ID);
        writeString(payload, "");
        writeByteArray(payload, keyPair.getPublic().getEncoded());
        writeByteArray(payload, verifyToken);
        var frame = Unpooled.buffer(MinecraftVarInts.encodedSize(payload.readableBytes()) + payload.readableBytes());
        MinecraftVarInts.write(frame, payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    private static byte[] readByteArray(ByteBuf input, int maxBytes) {
        var length = MinecraftProtocolCodec.readVarInt(input);
        if (length < 0 || length > maxBytes) {
            throw new IllegalArgumentException("byte array length out of bounds: " + length);
        }
        if (input.readableBytes() < length) {
            throw new IllegalArgumentException("truncated byte array");
        }
        var bytes = new byte[length];
        input.readBytes(bytes);
        return bytes;
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeByteArray(ByteBuf output, byte[] value) {
        MinecraftVarInts.write(output, value.length);
        output.writeBytes(value);
    }

    interface AuthenticatedLoginCallback {
        void authenticated(ChannelHandlerContext context, ByteBuf loginStartFrame, byte[] sharedSecret, String username);
    }
}

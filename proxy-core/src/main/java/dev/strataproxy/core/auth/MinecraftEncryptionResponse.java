package dev.strataproxy.core.auth;

import dev.strataproxy.core.protocol.ProtocolException;
import dev.strataproxy.core.protocol.ProtocolVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/** Protocol 5 login serverbound Encryption Response (packet id 0x01). */
public final class MinecraftEncryptionResponse {
    public static final int PACKET_ID = MinecraftEncryptionRequest.PACKET_ID;
    private final byte[] encryptedSharedSecret;
    private final byte[] encryptedVerifyToken;

    public MinecraftEncryptionResponse(byte[] encryptedSharedSecret, byte[] encryptedVerifyToken) {
        this.encryptedSharedSecret = MinecraftEncryptionRequest.copyBounded(encryptedSharedSecret, 1,
                MinecraftEncryptionRequest.MAX_RSA_CIPHERTEXT_BYTES, "encrypted shared secret");
        this.encryptedVerifyToken = MinecraftEncryptionRequest.copyBounded(encryptedVerifyToken, 1,
                MinecraftEncryptionRequest.MAX_RSA_CIPHERTEXT_BYTES, "encrypted verify token");
    }

    public static MinecraftEncryptionResponse decode(ByteBuf packet) {
        ByteBuf input = packet.duplicate();
        if (ProtocolVarInt.read(input) != PACKET_ID) throw new ProtocolException("expected Encryption Response packet id 1");
        byte[] secret = MinecraftEncryptionRequest.readUnsignedShortArray(input, 1,
                MinecraftEncryptionRequest.MAX_RSA_CIPHERTEXT_BYTES, "encrypted shared secret");
        byte[] token = MinecraftEncryptionRequest.readUnsignedShortArray(input, 1,
                MinecraftEncryptionRequest.MAX_RSA_CIPHERTEXT_BYTES, "encrypted verify token");
        if (input.isReadable()) throw new ProtocolException("trailing Encryption Response bytes");
        return new MinecraftEncryptionResponse(secret, token);
    }

    public ByteBuf encode(ByteBufAllocator allocator) {
        ByteBuf output = allocator.buffer(1 + 2 + encryptedSharedSecret.length + 2 + encryptedVerifyToken.length);
        ProtocolVarInt.write(output, PACKET_ID);
        MinecraftEncryptionRequest.writeUnsignedShortArray(output, encryptedSharedSecret);
        MinecraftEncryptionRequest.writeUnsignedShortArray(output, encryptedVerifyToken);
        return output;
    }

    public byte[] encryptedSharedSecret() { return encryptedSharedSecret.clone(); }
    public byte[] encryptedVerifyToken() { return encryptedVerifyToken.clone(); }
}

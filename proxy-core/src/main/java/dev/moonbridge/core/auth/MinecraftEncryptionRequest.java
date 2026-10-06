package dev.moonbridge.core.auth;

import dev.moonbridge.core.protocol.ByteBufs;
import dev.moonbridge.core.protocol.ProtocolException;
import dev.moonbridge.core.protocol.ProtocolVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;

/** Protocol 5 login clientbound Encryption Request (packet id 0x01). */
public final class MinecraftEncryptionRequest {
    public static final int PACKET_ID = 1;
    public static final int VERIFY_TOKEN_BYTES = 4;
    static final int MAX_ENCODED_PUBLIC_KEY_BYTES = 8192;
    static final int MAX_RSA_CIPHERTEXT_BYTES = 512;
    private final String serverId;
    private final byte[] publicKey;
    private final byte[] verifyToken;

    public MinecraftEncryptionRequest(String serverId, byte[] publicKey, byte[] verifyToken) {
        this.serverId = validateServerId(serverId);
        this.publicKey = copyBounded(publicKey, 1, MAX_ENCODED_PUBLIC_KEY_BYTES, "public key");
        if (verifyToken == null || verifyToken.length != VERIFY_TOKEN_BYTES) {
            throw new IllegalArgumentException("verify token must contain exactly 4 bytes");
        }
        this.verifyToken = Arrays.copyOf(verifyToken, verifyToken.length);
    }

    public static MinecraftEncryptionRequest create(String serverId, PublicKey publicKey, SecureRandom random) {
        if (publicKey == null || publicKey.getEncoded() == null) throw new IllegalArgumentException("publicKey must be encodable");
        if (random == null) throw new IllegalArgumentException("random is required");
        byte[] token = new byte[VERIFY_TOKEN_BYTES];
        random.nextBytes(token);
        return new MinecraftEncryptionRequest(serverId, publicKey.getEncoded(), token);
    }

    public static MinecraftEncryptionRequest decode(ByteBuf packet) {
        ByteBuf input = packet.duplicate();
        if (ProtocolVarInt.read(input) != PACKET_ID) throw new ProtocolException("expected Encryption Request packet id 1");
        int serverIdBytes = ProtocolVarInt.read(input);
        if (serverIdBytes < 0 || serverIdBytes > 20 || input.readableBytes() < serverIdBytes) {
            throw new ProtocolException("server id byte length out of bounds: " + serverIdBytes);
        }
        byte[] serverId = new byte[serverIdBytes];
        input.readBytes(serverId);
        String id = new String(serverId, StandardCharsets.US_ASCII);
        for (byte value : serverId) if (value < 0) throw new ProtocolException("server id must be ASCII");
        byte[] key = readUnsignedShortArray(input, 1, MAX_ENCODED_PUBLIC_KEY_BYTES, "public key");
        byte[] token = readUnsignedShortArray(input, VERIFY_TOKEN_BYTES, VERIFY_TOKEN_BYTES, "verify token");
        if (input.isReadable()) throw new ProtocolException("trailing Encryption Request bytes");
        return new MinecraftEncryptionRequest(id, key, token);
    }

    public ByteBuf encode(ByteBufAllocator allocator) {
        byte[] serverIdBytes = serverId.getBytes(StandardCharsets.US_ASCII);
        return ByteBufs.fill(allocator.buffer(
                1 + 1 + serverIdBytes.length + 2 + publicKey.length + 2 + verifyToken.length), output -> {
            ProtocolVarInt.write(output, PACKET_ID);
            ProtocolVarInt.write(output, serverIdBytes.length);
            output.writeBytes(serverIdBytes);
            writeUnsignedShortArray(output, publicKey);
            writeUnsignedShortArray(output, verifyToken);
        });
    }

    public String serverId() { return serverId; }
    public byte[] publicKey() { return publicKey.clone(); }
    public byte[] verifyToken() { return verifyToken.clone(); }

    static byte[] readUnsignedShortArray(ByteBuf input, int minimum, int maximum, String field) {
        if (input.readableBytes() < 2) throw new ProtocolException("truncated " + field + " length");
        int length = input.readUnsignedShort();
        if (length < minimum || length > maximum) throw new ProtocolException(field + " length out of bounds: " + length);
        if (input.readableBytes() < length) throw new ProtocolException("truncated " + field);
        byte[] bytes = new byte[length];
        input.readBytes(bytes);
        return bytes;
    }

    static void writeUnsignedShortArray(ByteBuf output, byte[] bytes) {
        output.writeShort(bytes.length);
        output.writeBytes(bytes);
    }

    static byte[] copyBounded(byte[] value, int minimum, int maximum, String field) {
        if (value == null || value.length < minimum || value.length > maximum) {
            throw new IllegalArgumentException(field + " length out of bounds");
        }
        return Arrays.copyOf(value, value.length);
    }

    private static String validateServerId(String value) {
        if (value == null || value.length() > 20) throw new IllegalArgumentException("serverId must be at most 20 ASCII characters");
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 0x7f) throw new IllegalArgumentException("serverId must be ASCII");
        return value;
    }
}

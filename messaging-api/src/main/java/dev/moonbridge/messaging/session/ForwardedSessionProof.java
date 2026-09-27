package dev.moonbridge.messaging.session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signed proof that a particular proxy session was forwarded to one backend. */
public final class ForwardedSessionProof {
    public static final String PROPERTY_NAME = "moonbridge:session";
    public static final long MAX_LIFETIME_MILLIS = 60_000L;
    private static final int VERSION = 1;
    private static final int MAX_TOKEN_LENGTH = 2048;
    private static final int MAX_BACKEND_NAME_BYTES = 128;

    private ForwardedSessionProof() { }

    public static String create(UUID proxyEpoch, UUID playerId, long connectionId, String backendName,
                                long backendEpoch,
                                UUID nonce, long expiresAtEpochMillis, byte[] secret) {
        Objects.requireNonNull(proxyEpoch, "proxyEpoch");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(backendName, "backendName");
        Objects.requireNonNull(nonce, "nonce");
        requireSecret(secret);
        if (connectionId < 0) throw new IllegalArgumentException("connectionId must be non-negative");
        if (backendEpoch < 0) throw new IllegalArgumentException("backendEpoch must be non-negative");
        byte[] backend = backendName.getBytes(StandardCharsets.UTF_8);
        if (backendName.trim().isEmpty() || backend.length > MAX_BACKEND_NAME_BYTES)
            throw new IllegalArgumentException("backendName must contain 1 to 128 UTF-8 bytes");
        if (expiresAtEpochMillis <= 0) throw new IllegalArgumentException("expiry must be positive");
        byte[] payload = encode(proxyEpoch, playerId, connectionId, backend, backendEpoch, nonce, expiresAtEpochMillis);
        byte[] signature = mac(secret, payload);
        String token = "1." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        if (token.length() > MAX_TOKEN_LENGTH) throw new IllegalArgumentException("session proof is too long");
        return token;
    }

    /** Returns empty for malformed, expired, misrouted, or unauthenticated proofs. */
    public static Optional<Claims> verify(String token, byte[] secret, String expectedBackendName,
                                          UUID expectedProxyEpoch, long expectedBackendEpoch,
                                          UUID expectedPlayerId, long nowEpochMillis) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH || expectedBackendName == null
                || expectedProxyEpoch == null || expectedBackendEpoch < 0 || expectedPlayerId == null || nowEpochMillis <= 0
                || secret == null || secret.length < 32) {
            return Optional.empty();
        }
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !"1".equals(parts[0])) return Optional.empty();
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            byte[] providedMac = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(mac(secret, payload), providedMac)) return Optional.empty();
            Claims claims = decode(payload);
            if (!claims.backendName.equals(expectedBackendName)
                    || !claims.proxyEpoch.equals(expectedProxyEpoch)
                    || claims.backendEpoch != expectedBackendEpoch
                    || !claims.playerId.equals(expectedPlayerId)
                    || claims.expiresAtEpochMillis <= nowEpochMillis
                    || claims.expiresAtEpochMillis - nowEpochMillis > MAX_LIFETIME_MILLIS) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (RuntimeException | IOException malformed) {
            return Optional.empty();
        }
    }

    private static byte[] encode(UUID proxyEpoch, UUID playerId, long connectionId, byte[] backend,
                                 long backendEpoch,
                                 UUID nonce, long expiresAtEpochMillis) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(96 + backend.length);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            writeUuid(out, proxyEpoch);
            writeUuid(out, playerId);
            out.writeLong(connectionId);
            out.writeShort(backend.length);
            out.write(backend);
            out.writeLong(backendEpoch);
            writeUuid(out, nonce);
            out.writeLong(expiresAtEpochMillis);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Claims decode(byte[] payload) throws IOException {
        if (payload.length < 1 + 16 + 16 + 8 + 2 + 1 + 8 + 16 + 8 || payload.length > 512)
            throw new IOException("invalid session proof length");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readUnsignedByte() != VERSION) throw new IOException("unsupported proof version");
        UUID proxyEpoch = readUuid(in);
        UUID playerId = readUuid(in);
        long connectionId = in.readLong();
        int backendLength = in.readUnsignedShort();
        if (backendLength < 1 || backendLength > MAX_BACKEND_NAME_BYTES || backendLength > in.available())
            throw new IOException("invalid backend name");
        byte[] backendBytes = new byte[backendLength];
        in.readFully(backendBytes);
        String backendName = new String(backendBytes, StandardCharsets.UTF_8);
        long backendEpoch = in.readLong();
        UUID nonce = readUuid(in);
        long expiry = in.readLong();
        if (in.available() != 0 || connectionId < 0 || backendEpoch < 0
                || backendName.trim().isEmpty() || expiry <= 0)
            throw new IOException("invalid session proof fields");
        return new Claims(proxyEpoch, playerId, connectionId, backendName, backendEpoch, nonce, expiry);
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static byte[] mac(byte[] secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", failure);
        }
    }

    private static void requireSecret(byte[] secret) {
        if (secret == null || secret.length < 32)
            throw new IllegalArgumentException("session binding secret must contain at least 32 bytes");
    }

    public static final class Claims {
        private final UUID proxyEpoch;
        private final UUID playerId;
        private final long connectionId;
        private final String backendName;
        private final long backendEpoch;
        private final UUID nonce;
        private final long expiresAtEpochMillis;

        private Claims(UUID proxyEpoch, UUID playerId, long connectionId, String backendName, long backendEpoch,
                       UUID nonce, long expiresAtEpochMillis) {
            this.proxyEpoch = proxyEpoch;
            this.playerId = playerId;
            this.connectionId = connectionId;
            this.backendName = backendName;
            this.backendEpoch = backendEpoch;
            this.nonce = nonce;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }

        public UUID proxyEpoch() { return proxyEpoch; }
        public UUID playerId() { return playerId; }
        public long connectionId() { return connectionId; }
        public String backendName() { return backendName; }
        public long backendEpoch() { return backendEpoch; }
        public UUID nonce() { return nonce; }
        public long expiresAtEpochMillis() { return expiresAtEpochMillis; }
    }
}

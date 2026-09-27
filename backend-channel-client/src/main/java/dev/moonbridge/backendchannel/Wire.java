package dev.moonbridge.backendchannel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Authentication and control-frame codecs for the backend connection. Business messages use MessageCodec. */
public final class Wire {
    public static final int VERSION = 3;
    public static final int HELLO = 1;
    public static final int REGISTER = 2;
    public static final int REGISTERED = 3;
    public static final int HEARTBEAT = 4;
    public static final int PONG = 5;
    public static final int GOODBYE = 6;

    public static final int NONCE_BYTES = 32;
    public static final int MAX_FRAME_BYTES = 66 * 1024;
    public static final int MAX_STRING_BYTES = 1024;

    private Wire() { }

    public static byte[] hello(int version, byte[] nonce) throws IOException {
        if (nonce == null || nonce.length != NONCE_BYTES) throw new IllegalArgumentException("nonce must be 32 bytes");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 + 4 + NONCE_BYTES);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(HELLO);
        out.writeInt(version);
        out.write(nonce);
        return bytes.toByteArray();
    }

    public static Hello decodeHello(byte[] frame) throws IOException {
        DataInputStream in = body(frame, HELLO);
        int version = in.readInt();
        byte[] nonce = new byte[NONCE_BYTES];
        in.readFully(nonce);
        requireEnd(in);
        return new Hello(version, nonce);
    }

    public static byte[] register(String instanceId, String name, String address, String generation,
                                  String keyId, byte[] signature) throws IOException {
        if (signature == null || signature.length != 32) throw new IllegalArgumentException("signature must be 32 bytes");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(REGISTER);
        writeString(out, instanceId);
        writeString(out, name);
        writeString(out, address);
        writeString(out, generation);
        writeString(out, keyId);
        out.write(signature);
        return bounded(bytes.toByteArray());
    }

    /** Canonical HMAC input after the server nonce: five length-prefixed UTF-8 strings. */
    public static byte[] canonicalRegistration(String instanceId, String name, String address,
                                               String generation, String keyId) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeString(out, instanceId);
        writeString(out, name);
        writeString(out, address);
        writeString(out, generation);
        writeString(out, keyId);
        return bytes.toByteArray();
    }

    public static Registration decodeRegister(byte[] frame) throws IOException {
        DataInputStream in = body(frame, REGISTER);
        String instanceId = readString(in);
        String name = readString(in);
        String address = readString(in);
        String generation = readString(in);
        String keyId = readString(in);
        byte[] signature = new byte[32];
        in.readFully(signature);
        requireEnd(in);
        return new Registration(instanceId, name, address, generation, keyId, signature);
    }

    public static byte[] registered(long epoch) throws IOException {
        return registered(epoch, new UUID(0L, 0L));
    }

    public static byte[] registered(long epoch, UUID proxyEpoch) throws IOException {
        if (epoch < 0 || proxyEpoch == null) throw new IllegalArgumentException("registered identity is invalid");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(25);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(REGISTERED);
        out.writeLong(epoch);
        out.writeLong(proxyEpoch.getMostSignificantBits());
        out.writeLong(proxyEpoch.getLeastSignificantBits());
        return bytes.toByteArray();
    }

    public static long decodeRegistered(byte[] frame) throws IOException {
        return decodeRegisteredIdentity(frame).epoch;
    }

    public static UUID decodeRegisteredProxyEpoch(byte[] frame) throws IOException {
        return decodeRegisteredIdentity(frame).proxyEpoch;
    }

    public static RegisteredIdentity decodeRegisteredIdentity(byte[] frame) throws IOException {
        DataInputStream in = body(frame, REGISTERED);
        long epoch = in.readLong();
        UUID proxyEpoch = new UUID(in.readLong(), in.readLong());
        requireEnd(in);
        if (epoch < 0) throw new IOException("invalid backend epoch");
        return new RegisteredIdentity(epoch, proxyEpoch);
    }

    public static final class RegisteredIdentity {
        public final long epoch;
        public final UUID proxyEpoch;
        private RegisteredIdentity(long epoch, UUID proxyEpoch) { this.epoch = epoch; this.proxyEpoch = proxyEpoch; }
    }

    public static byte[] empty(int type) throws IOException {
        if (type != HEARTBEAT && type != PONG && type != GOODBYE) throw new IllegalArgumentException("not an empty frame type");
        return new byte[] { (byte) type };
    }

    public static void validateEmpty(byte[] frame, int type) throws IOException {
        DataInputStream in = body(frame, type);
        requireEnd(in);
    }

    static DataInputStream body(byte[] frame, int type) throws IOException {
        if (frame == null || frame.length < 1 || frame.length > MAX_FRAME_BYTES || (frame[0] & 0xff) != type) {
            throw new IOException("invalid frame type or size");
        }
        return new DataInputStream(new ByteArrayInputStream(frame, 1, frame.length - 1));
    }

    private static byte[] bounded(byte[] frame) throws IOException {
        if (frame.length > MAX_FRAME_BYTES) throw new IOException("frame exceeds maximum size");
        return frame;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) throw new IllegalArgumentException("string cannot be null");
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > MAX_STRING_BYTES) throw new IllegalArgumentException("string length out of bounds");
        out.writeShort(encoded.length);
        out.write(encoded);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length == 0 || length > MAX_STRING_BYTES) throw new IOException("string length out of bounds");
        byte[] encoded = new byte[length];
        in.readFully(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static void requireEnd(DataInputStream in) throws IOException {
        if (in.read() != -1) throw new IOException("trailing frame data");
    }

    public static final class Hello {
        public final int version;
        public final byte[] nonce;
        Hello(int version, byte[] nonce) { this.version = version; this.nonce = nonce; }
    }

    public static final class Registration {
        public final String instanceId, name, address, generation, keyId;
        public final byte[] signature;
        Registration(String instanceId, String name, String address, String generation, String keyId, byte[] signature) {
            this.instanceId = instanceId; this.name = name; this.address = address; this.generation = generation;
            this.keyId = keyId; this.signature = signature;
        }
    }
}

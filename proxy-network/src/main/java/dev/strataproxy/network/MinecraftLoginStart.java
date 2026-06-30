package dev.strataproxy.network;

import io.netty.buffer.ByteBuf;

import java.util.Optional;

record MinecraftLoginStart(String username, Optional<ChatSessionKey> chatSessionKey) {
    MinecraftLoginStart {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        chatSessionKey = chatSessionKey == null ? Optional.empty() : chatSessionKey;
    }

    static MinecraftLoginStart read(ByteBuf fullFrame, int maxFrameBytes) {
        var duplicate = fullFrame.retainedDuplicate();
        try {
            var probe = MinecraftProtocolCodec.probeFrame(duplicate, maxFrameBytes);
            if (!probe.complete()) {
                throw new IllegalArgumentException("incomplete Login Start frame");
            }
            duplicate.skipBytes(probe.varIntBytes());
            var packetId = MinecraftProtocolCodec.readVarInt(duplicate);
            if (packetId != 0) {
                throw new IllegalArgumentException("expected Login Start packet, got " + packetId);
            }
            var username = MinecraftProtocolCodec.readString(duplicate, 16);
            return new MinecraftLoginStart(username, readChatSessionKey(duplicate));
        } finally {
            duplicate.release();
        }
    }

    private static Optional<ChatSessionKey> readChatSessionKey(ByteBuf input) {
        if (!input.isReadable()) {
            return Optional.empty();
        }
        var hasSignatureData = input.readBoolean();
        if (!hasSignatureData) {
            return Optional.empty();
        }
        var expiresAtEpochMillis = input.readLong();
        var encodedPublicKey = readByteArray(input, 512);
        var signature = readByteArray(input, 4096);
        return Optional.of(new ChatSessionKey(expiresAtEpochMillis, encodedPublicKey, signature));
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

    record ChatSessionKey(long expiresAtEpochMillis, byte[] encodedPublicKey, byte[] signature) {
        ChatSessionKey {
            if (encodedPublicKey == null || encodedPublicKey.length == 0 || encodedPublicKey.length > 512) {
                throw new IllegalArgumentException("encodedPublicKey must be 1..512 bytes");
            }
            if (signature == null || signature.length == 0 || signature.length > 4096) {
                throw new IllegalArgumentException("signature must be 1..4096 bytes");
            }
            encodedPublicKey = encodedPublicKey.clone();
            signature = signature.clone();
        }

        @Override
        public byte[] encodedPublicKey() {
            return encodedPublicKey.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }
}

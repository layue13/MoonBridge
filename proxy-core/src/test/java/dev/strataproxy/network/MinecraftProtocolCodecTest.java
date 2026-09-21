package dev.strataproxy.network;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftProtocolCodecTest {
    @Test
    void parsesHandshakeFrameWithoutConsumingOriginalBuffer() {
        var frame = handshakeFrame(763, "play.example.net", 25565, 2);
        var probe = MinecraftProtocolCodec.probeFrame(frame, 1024);

        var handshake = MinecraftProtocolCodec.readHandshake(frame, probe);

        assertTrue(probe.complete());
        assertEquals(763, handshake.protocolVersion());
        assertEquals("play.example.net", handshake.requestedHost());
        assertEquals(25565, handshake.requestedPort());
        assertEquals(2, handshake.nextState());
        assertEquals(0, frame.readerIndex());
    }

    @Test
    void reportsIncompleteFrame() {
        var partial = Unpooled.wrappedBuffer(new byte[] {(byte) 0x80});

        var probe = MinecraftProtocolCodec.probeFrame(partial, 1024);

        assertFalse(probe.complete());
    }

    private static io.netty.buffer.ByteBuf handshakeFrame(int protocol, String host, int port, int nextState) {
        var payload = Unpooled.buffer();
        writeVarInt(payload, 0);
        writeVarInt(payload, protocol);
        writeString(payload, host);
        payload.writeShort(port);
        writeVarInt(payload, nextState);

        var frame = Unpooled.buffer();
        writeVarInt(frame, payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    private static void writeString(io.netty.buffer.ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeVarInt(io.netty.buffer.ByteBuf output, int value) {
        var current = value;
        do {
            var temp = current & 0x7F;
            current >>>= 7;
            if (current != 0) {
                temp |= 0x80;
            }
            output.writeByte(temp);
        } while (current != 0);
    }
}

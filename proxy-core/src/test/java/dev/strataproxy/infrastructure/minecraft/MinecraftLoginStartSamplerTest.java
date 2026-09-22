package dev.strataproxy.infrastructure.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftLoginStartSamplerTest {
    @Test
    void parsesCompleteLoginStartFromSeededFragments() {
        var frame = loginStartFrame("Steve");
        var fullFrame = frame.readableBytes();
        var first = frame.readRetainedSlice(Math.max(1, fullFrame / 2));
        var second = frame.readRetainedSlice(frame.readableBytes());
        var sampler = new MinecraftLoginStartSampler(1024, first);
        try {
            assertEquals(java.util.Optional.empty(), sampler.observe(Unpooled.EMPTY_BUFFER));
            assertEquals(java.util.Optional.empty(), sampler.observe(Unpooled.EMPTY_BUFFER));
            assertEquals("Steve", sampler.observe(second).orElseThrow());
            assertTrue(sampler.observe(Unpooled.EMPTY_BUFFER).isEmpty());
        } finally {
            frame.release();
            first.release();
            second.release();
        }
    }

    @Test
    void observesLoginStartNormallyWithoutSeed() {
        var frame = loginStartFrame("Steve");
        var sampler = new MinecraftLoginStartSampler(1024);
        try {
            assertEquals("Steve", sampler.observe(frame).orElseThrow());
            assertEquals(java.util.Optional.empty(), sampler.observe(Unpooled.EMPTY_BUFFER));
            assertEquals(false, sampler.observe(Unpooled.EMPTY_BUFFER).isPresent());
        } finally {
            frame.release();
        }
    }

    private static ByteBuf loginStartFrame(String username) {
        var payload = Unpooled.buffer();
        writeVarInt(payload, 0);
        var usernameBytes = username.getBytes(StandardCharsets.UTF_8);
        writeVarInt(payload, usernameBytes.length);
        payload.writeBytes(usernameBytes);
        writeVarInt(payload, 0);
        var frame = Unpooled.buffer();
        writeVarInt(frame, payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    private static void writeVarInt(ByteBuf output, int value) {
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

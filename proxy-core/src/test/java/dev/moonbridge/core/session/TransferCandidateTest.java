package dev.moonbridge.core.session;

import dev.moonbridge.core.protocol.Minecraft1710PlayPackets;
import dev.moonbridge.core.protocol.ProtocolVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TransferCandidateTest {
    @Test
    void errorAfterHandoffClosesTheCandidateChannel() {
        UUID playerId = UUID.randomUUID();
        var ready = new AtomicBoolean();
        var candidate = new TransferCandidate(playerId, "Player", new TransferCandidate.Listener() {
            @Override public void ready(TransferCandidate ignored) { ready.set(true); }
            @Override public void failed(TransferCandidate ignored, String reason) {
                throw new AssertionError("candidate has already been handed off");
            }
        });
        var channel = new EmbeddedChannel(candidate);
        try {
            ByteBuf login = Unpooled.buffer();
            try {
                ProtocolVarInt.write(login, 2);
                writeString(login, playerId.toString());
                writeString(login, "Player");
                channel.writeInbound(Minecraft1710PlayPackets.frame(channel.alloc(), login));
            } finally { login.release(); }
            ByteBuf join = Unpooled.buffer();
            try {
                ProtocolVarInt.write(join, Minecraft1710PlayPackets.JOIN_GAME);
                join.writeInt(200).writeByte(0).writeByte(0).writeByte(1).writeByte(20);
                writeString(join, "default");
                channel.writeInbound(Minecraft1710PlayPackets.frame(channel.alloc(), join));
            } finally { join.release(); }
            assertTrue(ready.get());
            candidate.handOff();
            channel.pipeline().fireExceptionCaught(new IllegalStateException("backend read failed"));
            assertFalse(channel.isOpen());
        } finally {
            candidate.close();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void boundsTinyQueuedPacketsAndReleasesTheirBuffersOnFailure() {
        UUID playerId = UUID.randomUUID();
        var failure = new AtomicReference<String>();
        var candidate = new TransferCandidate(playerId, "Player", new TransferCandidate.Listener() {
            @Override public void ready(TransferCandidate ignored) {
                throw new AssertionError("candidate should not be ready");
            }

            @Override public void failed(TransferCandidate ignored, String reason) {
                failure.set(reason);
            }
        });
        var channel = new EmbeddedChannel(candidate);
        var frames = new ArrayList<ByteBuf>();
        try {
            ByteBuf login = Unpooled.buffer();
            try {
                ProtocolVarInt.write(login, 2);
                writeString(login, playerId.toString());
                writeString(login, "Player");
                channel.writeInbound(Minecraft1710PlayPackets.frame(channel.alloc(), login));
            } finally {
                login.release();
            }
            for (int i = 0; i <= 1024; i++) {
                ByteBuf frame = Unpooled.wrappedBuffer(new byte[]{1, 3});
                frames.add(frame);
                channel.writeInbound(frame);
            }
            assertFalse(channel.isOpen());
            assertEquals("backend sent too much data before transfer", failure.get());
            assertTrue(frames.stream().allMatch(frame -> frame.refCnt() == 0));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void writeString(ByteBuf output, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        ProtocolVarInt.write(output, encoded.length);
        output.writeBytes(encoded);
    }

    @Test
    void nonBufferMessagesAreReleasedAndFailTheCandidate() {
        var failure = new AtomicReference<String>();
        var candidate = new TransferCandidate(UUID.randomUUID(), "Player", new TransferCandidate.Listener() {
            @Override public void ready(TransferCandidate ignored) { throw new AssertionError("not ready"); }
            @Override public void failed(TransferCandidate ignored, String reason) { failure.set(reason); }
        });
        var channel = new EmbeddedChannel(candidate);
        var holder = new io.netty.buffer.DefaultByteBufHolder(io.netty.buffer.Unpooled.buffer().writeByte(1));
        channel.writeInbound(holder);
        assertEquals(0, holder.refCnt());
        assertNotNull(failure.get());
        channel.finishAndReleaseAll();
    }
}

package dev.moonbridge.core.session;

import dev.moonbridge.core.protocol.ByteBufs;
import dev.moonbridge.core.protocol.Minecraft1710EntityIds;
import dev.moonbridge.core.protocol.Minecraft1710PlayPackets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;

/** Maps frames buffered during relay start-up before they are written to their destination. */
final class TransitionFrames {
    private TransitionFrames() { }

    /**
     * Takes ownership of {@code payload} and returns the buffer to send, or null when the frame is
     * swallowed. The payload is released on every failure, so callers never touch it after an exception.
     */
    static ByteBuf map(PlayObservation observation, KeepAliveBridge.State keepAlives, boolean fromFrontend,
                       ByteBuf payload, Channel target) {
        try {
            if (observation != null) observation.observePacket(!fromFrontend, payload);
            if (target == null || !target.isActive()) throw new IllegalStateException("transition peer closed");
            ByteBuf mapped = keepAlives.body(target.alloc(), payload, fromFrontend);
            if (mapped != payload) payload.release();
            return mapped;
        } catch (RuntimeException failure) {
            payload.release();
            throw failure;
        }
    }

    /**
     * Frames {@code mapped} (borrowed), swaps the player's entity ids when the replacement backend assigned a
     * different one, and appends the result to {@code output}. Every temporary buffer is released.
     */
    static void appendClientbound(ByteBuf output, ByteBufAllocator allocator, ByteBuf mapped,
                                  Integer serverEntityId, Integer clientEntityId) {
        ByteBufs.use(Minecraft1710PlayPackets.frame(allocator, mapped), frame -> {
            ByteBuf outgoing = serverEntityId == null ? frame
                    : Minecraft1710EntityIds.rewrite(allocator, frame, true, serverEntityId, clientEntityId);
            try {
                output.writeBytes(outgoing);
            } finally {
                if (outgoing != frame) outgoing.release();
            }
            return null;
        });
    }
}

package dev.moonbridge.core.session.play;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Buffered start-up frames change owner here, so every outcome pins who releases the payload. */
final class TransitionFramesTest {
    private static ByteBuf packet(int... bytes) {
        ByteBuf buffer = UnpooledByteBufAllocator.DEFAULT.buffer();
        for (int value : bytes) buffer.writeByte(value);
        return buffer;
    }

    @Test
    void ordinaryPacketsPassThroughStillOwnedByTheCaller() {
        var target = new EmbeddedChannel();
        var payload = packet(0x03, 1, 2);
        assertSame(payload, TransitionFrames.map(null, new KeepAliveBridge.State(), true, payload, target));
        assertEquals(1, payload.refCnt());
        payload.release();
        target.finishAndReleaseAll();
    }

    @Test
    void aMappedKeepAliveReplacesAndReleasesTheOriginal() {
        var target = new EmbeddedChannel();
        var payload = packet(0x00, 0, 0, 0, 9); // backend keep-alive id 9
        ByteBuf mapped = TransitionFrames.map(null, new KeepAliveBridge.State(), false, payload, target);
        assertNotSame(payload, mapped);
        assertEquals(0, payload.refCnt());
        assertEquals(1, mapped.refCnt());
        mapped.release();
        target.finishAndReleaseAll();
    }

    @Test
    void anUnknownClientKeepAliveIsSwallowedAndReleased() {
        var target = new EmbeddedChannel();
        var payload = packet(0x00, 0, 0, 0, 9); // no backend keep-alive is pending
        assertNull(TransitionFrames.map(null, new KeepAliveBridge.State(), true, payload, target));
        assertEquals(0, payload.refCnt());
        target.finishAndReleaseAll();
    }

    @Test
    void releasesThePayloadWhenTheDestinationIsClosed() {
        var target = new EmbeddedChannel();
        target.close();
        var payload = packet(0x03, 1);
        assertThrows(IllegalStateException.class,
                () -> TransitionFrames.map(null, new KeepAliveBridge.State(), true, payload, target));
        assertEquals(0, payload.refCnt());
        assertThrows(IllegalStateException.class, () ->
                TransitionFrames.map(null, new KeepAliveBridge.State(), true, packet(0x03), null));
    }

    @Test
    void releasesThePayloadWhenObservationRejectsIt() {
        var target = new EmbeddedChannel();
        var payload = Unpooled.buffer(); // empty: no packet id to read
        assertThrows(RuntimeException.class,
                () -> TransitionFrames.map(new PlayObservation(), new KeepAliveBridge.State(), false, payload, target));
        assertEquals(0, payload.refCnt());
        target.finishAndReleaseAll();
    }

    @Test
    void releasesThePayloadWhenKeepAliveMappingRejectsIt() {
        var target = new EmbeddedChannel();
        var payload = packet(0x00, 1, 2); // keep-alive packet with a truncated id
        assertThrows(IllegalArgumentException.class,
                () -> TransitionFrames.map(null, new KeepAliveBridge.State(), false, payload, target));
        assertEquals(0, payload.refCnt());
        target.finishAndReleaseAll();
    }

    @Test
    void appendsAFramedPacketAndReleasesEveryTemporaryWhenTheEntityIdIsRewritten() {
        var tracking = new dev.moonbridge.testing.TrackingAllocator();
        var mapped = packet(0x0B, 0xC8, 0x01, 0x01); // Animation: entity VarInt 200, animation 1
        var output = Unpooled.buffer();
        TransitionFrames.appendClientbound(output, tracking, mapped, 200, 5);
        assertEquals(3, output.readByte());     // frame length
        assertEquals(0x0B, output.readByte());
        assertEquals(5, output.readByte());     // one-byte VarInt: the rewrite changed the frame size
        assertEquals(1, output.readByte());
        assertEquals(1, mapped.refCnt(), "the borrowed packet stays with the caller");
        assertEquals(0, output.readableBytes());
        org.junit.jupiter.api.Assertions.assertTrue(tracking.allReleased(), String.valueOf(tracking.referenceCounts()));
        mapped.release();
        output.release();
    }

    @Test
    void appendsAnUnchangedFrameWhenThereIsNoJoinGame() {
        var tracking = new dev.moonbridge.testing.TrackingAllocator();
        var mapped = packet(0x03, 7);
        var output = Unpooled.buffer();
        TransitionFrames.appendClientbound(output, tracking, mapped, null, null);
        assertEquals(2, output.readByte());
        assertEquals(0x03, output.readByte());
        assertEquals(7, output.readByte());
        org.junit.jupiter.api.Assertions.assertTrue(tracking.allReleased(), String.valueOf(tracking.referenceCounts()));
        mapped.release();
        output.release();
    }
}

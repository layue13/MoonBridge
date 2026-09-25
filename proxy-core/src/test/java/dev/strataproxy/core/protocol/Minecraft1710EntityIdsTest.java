package dev.strataproxy.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class Minecraft1710EntityIdsTest {
    @Test
    void releasesReadOnlyReplacementWhenLaterEntityFieldIsTruncated() {
        var allocator = new TrackingAllocator();
        ByteBuf frame = Unpooled.buffer();
        ProtocolVarInt.write(frame, 5);
        ProtocolVarInt.write(frame, 0x1B);
        frame.writeInt(200);
        try {
            assertThrows(ProtocolException.class, () -> Minecraft1710EntityIds.rewrite(
                    allocator, frame.asReadOnly(), true, 200, 100));
            assertEquals(1, allocator.buffers.size());
            assertEquals(0, allocator.buffers.get(0).refCnt());
        } finally {
            for (ByteBuf buffer : allocator.buffers) {
                if (buffer.refCnt() > 0) buffer.release(buffer.refCnt());
            }
            frame.release();
        }
    }

    @Test
    void releasesVarIntReplacementWhenLaterSpawnObjectFieldsAreTruncated() {
        var allocator = new TrackingAllocator();
        ByteBuf frame = Unpooled.buffer();
        ProtocolVarInt.write(frame, 3);
        ProtocolVarInt.write(frame, 0x0E);
        ProtocolVarInt.write(frame, 200);
        try {
            assertThrows(ProtocolException.class, () -> Minecraft1710EntityIds.rewrite(
                    allocator, frame, true, 200, 100));
            assertEquals(1, allocator.buffers.size());
            assertEquals(0, allocator.buffers.get(0).refCnt());
        } finally {
            for (ByteBuf buffer : allocator.buffers) {
                if (buffer.refCnt() > 0) buffer.release(buffer.refCnt());
            }
            frame.release();
        }
    }

    @Test
    void readOnlyFrameWithNonzeroReaderIndexSwapsBothEntityFields() {
        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(0x7f);
        ProtocolVarInt.write(frame, 9);
        ProtocolVarInt.write(frame, 0x1B);
        frame.writeInt(200).writeInt(100);
        frame.readerIndex(1);
        ByteBuf rewritten = null;
        try {
            rewritten = Minecraft1710EntityIds.rewrite(UnpooledByteBufAllocator.DEFAULT,
                    frame.asReadOnly(), true, 200, 100);
            ByteBuf body = rewritten.duplicate();
            assertEquals(9, ProtocolVarInt.read(body));
            assertEquals(0x1B, ProtocolVarInt.read(body));
            assertEquals(100, body.readInt());
            assertEquals(200, body.readInt());
        } finally {
            if (rewritten != null && rewritten != frame) rewritten.release();
            frame.release();
        }
    }

    @Test
    void readOnlyDestroyEntitiesFrameKeepsOffsetsAfterFirstCopy() {
        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(0x7f);
        ProtocolVarInt.write(frame, 14);
        ProtocolVarInt.write(frame, 0x13);
        frame.writeByte(3).writeInt(200).writeInt(100).writeInt(300);
        frame.readerIndex(1);
        ByteBuf rewritten = null;
        try {
            rewritten = Minecraft1710EntityIds.rewrite(UnpooledByteBufAllocator.DEFAULT,
                    frame.asReadOnly(), true, 200, 100);
            ByteBuf body = rewritten.duplicate();
            assertEquals(14, ProtocolVarInt.read(body));
            assertEquals(0x13, ProtocolVarInt.read(body));
            assertEquals(3, body.readUnsignedByte());
            assertEquals(100, body.readInt());
            assertEquals(200, body.readInt());
            assertEquals(300, body.readInt());
        } finally {
            if (rewritten != null && rewritten != frame) rewritten.release();
            frame.release();
        }
    }

    @Test
    void swapsFixedWidthIdsInBothDirectionsAndLeavesUnrelatedFramesUntouched() {
        ByteBuf clientbound = frame(0x1A, 200, 1);
        ByteBuf serverbound = frame(0x0B, 100, 2);
        ByteBuf unrelated = frame(0x1A, 300, 1);
        try {
            assertSame(clientbound, Minecraft1710EntityIds.rewrite(UnpooledByteBufAllocator.DEFAULT,
                    clientbound, true, 200, 100));
            assertEquals(100, firstInt(clientbound));
            assertSame(serverbound, Minecraft1710EntityIds.rewrite(UnpooledByteBufAllocator.DEFAULT,
                    serverbound, false, 200, 100));
            assertEquals(200, firstInt(serverbound));
            assertSame(unrelated, Minecraft1710EntityIds.rewrite(UnpooledByteBufAllocator.DEFAULT,
                    unrelated, true, 200, 100));
            assertEquals(300, firstInt(unrelated));
        } finally {
            clientbound.release();
            serverbound.release();
            unrelated.release();
        }
    }

    @Test
    void updatesFrameLengthWhenVarIntWidthChangesAndResolvesIdCollision() {
        ByteBuf original = Unpooled.buffer();
        ByteBuf changed = null;
        try {
            ByteBuf body = Unpooled.buffer();
            try {
                ProtocolVarInt.write(body, 0x0C); // Spawn Player.
                ProtocolVarInt.write(body, 200);
                body.writeByte(0x66);
                ProtocolVarInt.write(original, body.readableBytes());
                original.writeBytes(body);
            } finally {
                body.release();
            }
            changed = Minecraft1710EntityIds.rewrite(UnpooledByteBufAllocator.DEFAULT,
                    original, true, 200, 100);
            assertNotSame(original, changed);
            ByteBuf decoded = changed.duplicate();
            assertEquals(3, ProtocolVarInt.read(decoded));
            assertEquals(0x0C, ProtocolVarInt.read(decoded));
            assertEquals(100, ProtocolVarInt.read(decoded));
            assertEquals(0x66, decoded.readUnsignedByte());

            ByteBuf collision = frame(0x1A, 100, 1);
            try {
                Minecraft1710EntityIds.rewrite(UnpooledByteBufAllocator.DEFAULT,
                        collision, true, 200, 100);
                assertEquals(200, firstInt(collision));
            } finally {
                collision.release();
            }
        } finally {
            if (changed != null && changed != original) changed.release();
            original.release();
        }
    }

    @Test
    void rewritesEveryDestroyEntityIdAndBothAttachEntityIds() {
        ByteBuf destroyed = Unpooled.buffer();
        ByteBuf attached = Unpooled.buffer();
        try {
            ProtocolVarInt.write(destroyed, 14);
            ProtocolVarInt.write(destroyed, 0x13);
            destroyed.writeByte(3).writeInt(200).writeInt(100).writeInt(300);
            Minecraft1710EntityIds.rewrite(UnpooledByteBufAllocator.DEFAULT, destroyed, true, 200, 100);
            ByteBuf ids = destroyed.duplicate();
            ProtocolVarInt.read(ids);
            ProtocolVarInt.read(ids);
            assertEquals(3, ids.readUnsignedByte());
            assertEquals(100, ids.readInt());
            assertEquals(200, ids.readInt());
            assertEquals(300, ids.readInt());

            ProtocolVarInt.write(attached, 10);
            ProtocolVarInt.write(attached, 0x1B);
            attached.writeInt(200).writeInt(100).writeByte(0);
            Minecraft1710EntityIds.rewrite(UnpooledByteBufAllocator.DEFAULT, attached, true, 200, 100);
            ByteBuf pair = attached.duplicate();
            ProtocolVarInt.read(pair);
            ProtocolVarInt.read(pair);
            assertEquals(100, pair.readInt());
            assertEquals(200, pair.readInt());
        } finally {
            destroyed.release();
            attached.release();
        }
    }

    @Test
    void rewritesSpawnObjectShooterIdsWithoutChangingOtherObjectData() {
        for (int type : new int[]{60, 63, 64, 66, 90, 73}) {
            ByteBuf spawned = spawnObject(type, 200);
            try {
                assertSame(spawned, Minecraft1710EntityIds.rewrite(UnpooledByteBufAllocator.DEFAULT,
                        spawned, true, 200, 100));
                ByteBuf body = spawned.duplicate();
                ProtocolVarInt.read(body);
                assertEquals(0x0E, ProtocolVarInt.read(body));
                assertEquals(300, ProtocolVarInt.read(body));
                assertEquals(type, body.readUnsignedByte());
                body.skipBytes(14); // Position, pitch, and yaw.
                assertEquals(type == 73 ? 200 : 100, body.readInt());
            } finally {
                spawned.release();
            }
        }
    }

    private static ByteBuf spawnObject(int type, int objectData) {
        ByteBuf body = Unpooled.buffer();
        ByteBuf frame = Unpooled.buffer();
        try {
            ProtocolVarInt.write(body, 0x0E);
            ProtocolVarInt.write(body, 300);
            body.writeByte(type).writeInt(1).writeInt(2).writeInt(3);
            body.writeByte(4).writeByte(5).writeInt(objectData);
            body.writeShort(0).writeShort(0).writeShort(0);
            ProtocolVarInt.write(frame, body.readableBytes());
            frame.writeBytes(body);
            return frame;
        } catch (RuntimeException failure) {
            frame.release();
            throw failure;
        } finally {
            body.release();
        }
    }

    private static ByteBuf frame(int packetId, int entityId, int trailingByte) {
        ByteBuf frame = Unpooled.buffer();
        ProtocolVarInt.write(frame, 6);
        ProtocolVarInt.write(frame, packetId);
        frame.writeInt(entityId).writeByte(trailingByte);
        return frame;
    }

    private static int firstInt(ByteBuf frame) {
        ByteBuf decoded = frame.duplicate();
        ProtocolVarInt.read(decoded);
        ProtocolVarInt.read(decoded);
        return decoded.readInt();
    }

    private static final class TrackingAllocator extends AbstractByteBufAllocator {
        private final List<ByteBuf> buffers = new ArrayList<>();

        private TrackingAllocator() { super(false); }

        @Override public boolean isDirectBufferPooled() { return false; }

        @Override protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            ByteBuf buffer = Unpooled.buffer(initialCapacity, maxCapacity);
            buffers.add(buffer);
            return buffer;
        }

        @Override protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            ByteBuf buffer = Unpooled.directBuffer(initialCapacity, maxCapacity);
            buffers.add(buffer);
            return buffer;
        }
    }
}

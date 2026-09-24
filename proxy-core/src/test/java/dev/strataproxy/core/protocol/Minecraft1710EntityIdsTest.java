package dev.strataproxy.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

final class Minecraft1710EntityIdsTest {
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
}

package dev.strataproxy.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/** Rewrites the player's entity ID after a protocol 5 backend switch. */
public final class Minecraft1710EntityIds {
    private Minecraft1710EntityIds() { }

    /** Returns the original owned frame when unchanged, or a newly owned replacement frame. */
    public static ByteBuf rewrite(ByteBufAllocator allocator, ByteBuf frame, boolean clientbound,
                                  int serverEntityId, int clientEntityId) {
        if (serverEntityId == clientEntityId) return frame;
        ByteBuf input = frame.duplicate();
        int length = ProtocolVarInt.read(input);
        if (length < 1 || length != input.readableBytes()) throw new ProtocolException("invalid entity frame length");
        int bodyStart = input.readerIndex();
        int packetId = ProtocolVarInt.read(input);
        int firstField = input.readerIndex();
        ByteBuf current = frame;
        if (varIntFirst(packetId, clientbound)) {
            int entityId = ProtocolVarInt.read(input);
            int replacement = swapped(entityId, serverEntityId, clientEntityId);
            if (replacement != entityId) {
                int oldEntityBytes = input.readerIndex() - firstField;
                int newBodyBytes = length - oldEntityBytes + ProtocolVarInt.encodedSize(replacement);
                ByteBuf changed = allocator.buffer(ProtocolVarInt.encodedSize(newBodyBytes) + newBodyBytes);
                try {
                    ProtocolVarInt.write(changed, newBodyBytes);
                    changed.writeBytes(frame, bodyStart, firstField - bodyStart);
                    ProtocolVarInt.write(changed, replacement);
                    changed.writeBytes(frame, input.readerIndex(), frame.writerIndex() - input.readerIndex());
                    current = changed;
                } catch (RuntimeException failure) {
                    changed.release();
                    throw failure;
                }
            }
        } else if (intFirst(packetId, clientbound)) {
            current = rewriteInt(allocator, current, firstField, serverEntityId, clientEntityId);
        }
        if (clientbound) {
            if (packetId == 0x0D || packetId == 0x1B) {
                current = rewriteInt(allocator, current, firstField + 4, serverEntityId, clientEntityId);
            } else if (packetId == 0x13) {
                ByteBuf body = current.duplicate();
                ProtocolVarInt.read(body);
                ProtocolVarInt.read(body);
                if (!body.isReadable()) throw new ProtocolException("missing Destroy Entities count");
                int count = body.readUnsignedByte();
                if (body.readableBytes() < count * 4) throw new ProtocolException("truncated Destroy Entities");
                for (int i = 0; i < count; i++) {
                    current = rewriteInt(allocator, current, body.readerIndex() + i * 4,
                            serverEntityId, clientEntityId);
                }
            } else if (packetId == 0x0E) {
                ByteBuf body = current.duplicate();
                ProtocolVarInt.read(body);
                ProtocolVarInt.read(body);
                ProtocolVarInt.read(body); // Object entity ID.
                if (body.readableBytes() < 18) throw new ProtocolException("truncated Spawn Object");
                int type = body.readUnsignedByte();
                if (type == 60 || type == 63 || type == 64 || type == 66 || type == 90) {
                    int objectDataOffset = body.readerIndex() + 14;
                    current = rewriteInt(allocator, current, objectDataOffset,
                            serverEntityId, clientEntityId);
                }
            }
        }
        return current;
    }

    private static boolean intFirst(int packetId, boolean clientbound) {
        if (!clientbound) return packetId == 0x02 || packetId == 0x0A || packetId == 0x0B;
        return switch (packetId) {
            case 0x04, 0x0A, 0x0D, 0x12, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19,
                    0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x20 -> true;
            default -> false;
        };
    }

    private static boolean varIntFirst(int packetId, boolean clientbound) {
        if (!clientbound) return false;
        return switch (packetId) {
            case 0x0B, 0x0C, 0x0E, 0x0F, 0x10, 0x11, 0x25, 0x2C -> true;
            default -> false;
        };
    }

    private static int swapped(int value, int serverEntityId, int clientEntityId) {
        if (value == serverEntityId) return clientEntityId;
        if (value == clientEntityId) return serverEntityId;
        return value;
    }

    private static ByteBuf rewriteInt(ByteBufAllocator allocator, ByteBuf frame, int offset,
                                      int serverEntityId, int clientEntityId) {
        if (offset < frame.readerIndex() || offset > frame.writerIndex() - 4) {
            throw new ProtocolException("truncated entity ID field");
        }
        int original = frame.getInt(offset);
        int replacement = swapped(original, serverEntityId, clientEntityId);
        if (replacement == original) return frame;
        if (frame.isReadOnly()) {
            int originalReaderIndex = frame.readerIndex();
            ByteBuf copy = allocator.buffer(frame.readableBytes());
            copy.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
            frame = copy;
            offset -= originalReaderIndex;
        }
        frame.setInt(offset, replacement);
        return frame;
    }
}

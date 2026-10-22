package dev.moonbridge.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.util.ArrayList;
import java.util.List;

/** Protocol 5 completion packets contain no request identifier. */
public final class MinecraftTabCompletion {
    public static final int REQUEST = 0x14;
    public static final int RESPONSE = 0x3a;
    private MinecraftTabCompletion() { }

    public static boolean matches(ByteBuf frame, int packetId) {
        int offset = frame.readerIndex();
        for (int i = 0; i < 3 && offset < frame.writerIndex(); i++) {
            if ((frame.getUnsignedByte(offset++) & 0x80) == 0) {
                return offset < frame.writerIndex() && frame.getUnsignedByte(offset) == packetId;
            }
        }
        return false;
    }

    private static ByteBuf body(ByteBuf frame, int id) {
        ByteBuf input = frame.duplicate();
        if (ProtocolVarInt.read(input) != input.readableBytes() || ProtocolVarInt.read(input) != id) {
            throw new ProtocolException("invalid completion packet");
        }
        return input;
    }

    public static String request(ByteBuf frame) {
        ByteBuf input = body(frame, REQUEST);
        String text = ProtocolStrings.read(input, 32767);
        if (input.isReadable()) throw new ProtocolException("trailing completion request bytes");
        return text;
    }

    public static List<String> response(ByteBuf frame) {
        ByteBuf input = body(frame, RESPONSE);
        int count = ProtocolVarInt.read(input);
        if (count < 0 || count > 4096) throw new ProtocolException("too many backend completions");
        List<String> result = new ArrayList<>(Math.min(count, 100));
        for (int i = 0; i < count; i++) {
            String text = ProtocolStrings.read(input, 32767);
            if (result.size() < 100 && text.length() <= 100) result.add(text);
        }
        if (input.isReadable()) throw new ProtocolException("trailing completion response bytes");
        return result;
    }

    public static ByteBuf response(ByteBufAllocator allocator, List<String> suggestions) {
        return ByteBufs.use(allocator.buffer(), body -> {
            if (suggestions.size() > 100) throw new IllegalArgumentException("too many completions");
            ProtocolVarInt.write(body, RESPONSE);
            ProtocolVarInt.write(body, suggestions.size());
            for (String suggestion : suggestions) ProtocolStrings.write(body, suggestion, 100);
            if (body.readableBytes() > 32767) throw new IllegalArgumentException("completion response too large");
            return Minecraft1710PlayPackets.frame(allocator, body);
        });
    }
}

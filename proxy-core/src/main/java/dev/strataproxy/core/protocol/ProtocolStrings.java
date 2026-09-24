package dev.strataproxy.core.protocol;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;

final class ProtocolStrings {
    private ProtocolStrings() { }

    static String read(ByteBuf input, int maxCharacters) {
        int length = ProtocolVarInt.read(input);
        if (length < 0 || length > maxCharacters * 4) throw new ProtocolException("string byte length out of bounds: " + length);
        if (input.readableBytes() < length) throw new ProtocolException("truncated string");
        String value = input.toString(input.readerIndex(), length, StandardCharsets.UTF_8);
        input.skipBytes(length);
        if (value.length() > maxCharacters) throw new ProtocolException("string character length out of bounds: " + value.length());
        return value;
    }

    static void write(ByteBuf output, String value, int maxCharacters) {
        if (value == null || value.length() > maxCharacters) throw new ProtocolException("string character length out of bounds");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxCharacters * 4) throw new ProtocolException("string byte length out of bounds");
        ProtocolVarInt.write(output, bytes.length);
        output.writeBytes(bytes);
    }
}

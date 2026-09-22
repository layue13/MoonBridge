package dev.strataproxy.infrastructure.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class ProxyProtocolV1Handler extends ByteToMessageDecoder {
    private static final int MAX_HEADER_BYTES = 108;
    private static final byte CR = '\r';
    private static final byte LF = '\n';

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        var lineEnd = findLineEnd(input);
        if (lineEnd < 0) {
            if (input.readableBytes() > MAX_HEADER_BYTES) {
                context.close();
            }
            return;
        }

        var lineLength = lineEnd - input.readerIndex();
        var line = input.toString(input.readerIndex(), lineLength, StandardCharsets.US_ASCII);
        input.skipBytes(lineLength + 2);
        try {
            context.channel().attr(ClientAddress.PROXY_PROTOCOL_ADDRESS).set(parse(line));
            context.pipeline().remove(this);
            context.fireUserEventTriggered(ProxyProtocolReady.INSTANCE);
        } catch (RuntimeException exception) {
            context.close();
        }
    }

    private static int findLineEnd(ByteBuf input) {
        var start = input.readerIndex();
        var end = input.writerIndex() - 1;
        for (var index = start; index < end; index++) {
            if (input.getByte(index) == CR && input.getByte(index + 1) == LF) {
                return index;
            }
        }
        return -1;
    }

    private static InetSocketAddress parse(String line) {
        var parts = line.split(" ");
        if (parts.length < 2 || !parts[0].equals("PROXY")) {
            throw new IllegalArgumentException("invalid PROXY header");
        }
        if (parts[1].equals("UNKNOWN")) {
            return InetSocketAddress.createUnresolved("unknown", 0);
        }
        if (parts.length != 6) {
            throw new IllegalArgumentException("invalid PROXY address fields");
        }
        if (!parts[1].equals("TCP4") && !parts[1].equals("TCP6")) {
            throw new IllegalArgumentException("unsupported PROXY family");
        }
        var sourcePort = Integer.parseInt(parts[4]);
        if (sourcePort < 0 || sourcePort > 65535) {
            throw new IllegalArgumentException("invalid PROXY source port");
        }
        return InetSocketAddress.createUnresolved(parts[2], sourcePort);
    }

    enum ProxyProtocolReady {
        INSTANCE
    }
}

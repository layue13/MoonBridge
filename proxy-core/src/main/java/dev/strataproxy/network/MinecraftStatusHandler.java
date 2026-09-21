package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class MinecraftStatusHandler extends ByteToMessageDecoder {
    private static final int STATUS_REQUEST_PACKET_ID = 0x00;
    private static final int STATUS_RESPONSE_PACKET_ID = 0x00;
    private static final int PING_REQUEST_PACKET_ID = 0x01;
    private static final int PONG_RESPONSE_PACKET_ID = 0x01;

    private final int maxFrameBytes;
    private final MinecraftStatusRuntime statusRuntime;

    MinecraftStatusHandler(int maxFrameBytes, MinecraftStatusRuntime statusRuntime) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.statusRuntime = statusRuntime == null ? MinecraftStatusRuntime.disabled() : statusRuntime;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        while (input.isReadable()) {
            var probe = MinecraftProtocolCodec.probeFrame(input, maxFrameBytes);
            if (!probe.complete()) {
                return;
            }
            var frame = input.readRetainedSlice(probe.totalBytes());
            try {
                handleFrame(context, frame, probe);
            } finally {
                frame.release();
            }
        }
    }

    private void handleFrame(ChannelHandlerContext context, ByteBuf frame, MinecraftProtocolCodec.FrameProbe probe) {
        var payload = frame.retainedDuplicate();
        try {
            payload.skipBytes(probe.varIntBytes());
            var packetId = MinecraftProtocolCodec.readVarInt(payload);
            if (packetId == STATUS_REQUEST_PACKET_ID) {
                context.writeAndFlush(statusResponseFrame(statusRuntime.responseJson()));
            } else if (packetId == PING_REQUEST_PACKET_ID) {
                if (payload.readableBytes() != Long.BYTES) {
                    throw new IllegalArgumentException("status ping payload must be 8 bytes");
                }
                var value = payload.readLong();
                context.writeAndFlush(pongFrame(value)).addListener(future -> context.close());
            } else {
                throw new IllegalArgumentException("unexpected status packet id: " + packetId);
            }
        } catch (RuntimeException exception) {
            context.fireExceptionCaught(exception);
            context.close();
        } finally {
            payload.release();
        }
    }

    private static ByteBuf statusResponseFrame(String json) {
        var payload = Unpooled.buffer();
        MinecraftVarInts.write(payload, STATUS_RESPONSE_PACKET_ID);
        writeString(payload, json);
        return frame(payload);
    }

    private static ByteBuf pongFrame(long value) {
        var payload = Unpooled.buffer();
        MinecraftVarInts.write(payload, PONG_RESPONSE_PACKET_ID);
        payload.writeLong(value);
        return frame(payload);
    }

    private static ByteBuf frame(ByteBuf payload) {
        var frame = Unpooled.buffer(MinecraftVarInts.encodedSize(payload.readableBytes()) + payload.readableBytes());
        MinecraftVarInts.write(frame, payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }
}

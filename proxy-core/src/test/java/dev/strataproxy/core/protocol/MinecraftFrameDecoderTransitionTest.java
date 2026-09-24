package dev.strataproxy.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises the cleartext-to-ciphertext boundary when both arrive in one TCP read. */
class MinecraftFrameDecoderTransitionTest {
    @Test
    void decoderRemovalPassesRemainingBytesThroughNewCipherBeforeFraming() {
        List<Integer> received = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("clear-frame", new MinecraftFrameDecoder(ProtocolProfile.minecraft1710()));
        channel.pipeline().addLast("negotiation", new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext context, Object message) {
                        ByteBuf packet = (ByteBuf) message;
                        try {
                            received.add((int) packet.readUnsignedByte());
                            if (received.size() == 1) {
                                context.pipeline().addAfter("clear-frame", "cipher", new XorDecoder());
                                context.pipeline().addAfter("cipher", "encrypted-frame",
                                        new MinecraftFrameDecoder(ProtocolProfile.minecraft1710()));
                                context.pipeline().remove("clear-frame");
                            }
                        } finally {
                            packet.release();
                        }
                    }
                });
        ByteBuf input = Unpooled.buffer().writeByte(1).writeByte(0x11)
                .writeByte(1 ^ 0x55).writeByte(0x22 ^ 0x55);
        try {
            channel.writeInbound(input);
            assertEquals(List.of(0x11, 0x22), received);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static final class XorDecoder extends ByteToMessageDecoder {
        @Override protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
            if (!input.isReadable()) return;
            ByteBuf clear = context.alloc().buffer(input.readableBytes());
            while (input.isReadable()) clear.writeByte(input.readByte() ^ 0x55);
            output.add(clear);
        }
    }
}

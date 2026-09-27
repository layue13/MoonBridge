package dev.moonbridge.core.session;

import dev.moonbridge.core.protocol.Minecraft1710PlayPackets;
import dev.moonbridge.core.protocol.ProtocolVarInt;
import dev.moonbridge.core.relay.RawRelay;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class TransferFrameHandlerTest {
    @Test
    void consumingReplacementJoinGameRequestsTheNextBackendRead() {
        var reads = new AtomicInteger();
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void read(ChannelHandlerContext ctx) {
                reads.incrementAndGet();
                ctx.read();
            }
        });
        var observation = new PlayObservation();
        var state = new TransferFrameHandler.State(frontend, backend, observation, 100, null, () -> { });
        try {
            backend.pipeline().addLast("transfer-frame-handler", new TransferFrameHandler(state, true));
            RawRelay.attach(frontend, backend).start();
            pump(frontend, backend);
            int beforeJoin = reads.get();
            assertTrue(beforeJoin > 0);

            ByteBuf packet = Unpooled.buffer();
            try {
                ProtocolVarInt.write(packet, Minecraft1710PlayPackets.JOIN_GAME);
                packet.writeInt(200).writeByte(0).writeByte(0).writeByte(1).writeByte(20);
                byte[] level = "default".getBytes(StandardCharsets.UTF_8);
                ProtocolVarInt.write(packet, level.length);
                packet.writeBytes(level);
                backend.writeInbound(Minecraft1710PlayPackets.frame(backend.alloc(), packet));
            } finally { packet.release(); }
            pump(frontend, backend);
            assertTrue(reads.get() > beforeJoin, "the relay must read again after Join Game is consumed");
        } finally {
            state.close();
            observation.close();
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    private static void pump(EmbeddedChannel first, EmbeddedChannel second) {
        first.runPendingTasks();
        second.runPendingTasks();
        first.runPendingTasks();
        second.runPendingTasks();
    }
}

package dev.strataproxy.network;

import dev.strataproxy.network.MinecraftCompressionCodec;
import dev.strataproxy.network.MinecraftVarInts;
import dev.strataproxy.command.DefaultCommandRegistry;
import dev.strataproxy.plugin.command.CommandResult;
import dev.strataproxy.plugin.command.CommandSource;
import dev.strataproxy.plugin.command.CommandSpec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GameCommandFrameInterceptorTest {
    @Test
    void suppressesRegisteredUncompressedCommand() {
        var executions = new AtomicInteger();
        var registry = registry(executions);
        try (var interceptor = new GameCommandFrameInterceptor(registry, null, 1024)) {
            var frame = commandFrame(0x04, "server survival-2");

            var result = interceptor.intercept(UnpooledByteBufAllocator.DEFAULT, frame, false, -1, source());

            assertFalse(result.forward());
            assertEquals(1, executions.get());
            frame.release();
        }
    }

    @Test
    void suppressesLegacyUncompressedCommand() {
        var executions = new AtomicInteger();
        var registry = registry(executions);
        try (var interceptor = new GameCommandFrameInterceptor(
                registry,
                null,
                1024,
                MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10))) {
            var frame = commandFrame(0x01, "server survival-2");

            var result = interceptor.intercept(UnpooledByteBufAllocator.DEFAULT, frame, false, -1, source());

            assertFalse(result.forward());
            assertEquals(1, executions.get());
            frame.release();
        }
    }

    @Test
    void forwardsLegacyPacketIdOnModernProfile() {
        var executions = new AtomicInteger();
        var registry = registry(executions);
        try (var interceptor = new GameCommandFrameInterceptor(registry, null, 1024)) {
            var frame = commandFrame(0x01, "server survival-2");

            var result = interceptor.intercept(UnpooledByteBufAllocator.DEFAULT, frame, false, -1, source());

            assertTrue(result.forward());
            assertEquals(0, executions.get());
            assertEquals(frame.readableBytes(), result.message().readableBytes());
            result.message().release();
        }
    }

    @Test
    void forwardsUnknownUncompressedCommand() {
        var registry = registry(new AtomicInteger());
        try (var interceptor = new GameCommandFrameInterceptor(registry, null, 1024)) {
            var frame = commandFrame(0x04, "spawn");

            var result = interceptor.intercept(UnpooledByteBufAllocator.DEFAULT, frame, false, -1, source());

            assertTrue(result.forward());
            assertEquals(frame.readableBytes(), result.message().readableBytes());
            result.message().release();
        }
    }

    @Test
    void forwardsUnknownLegacyUncompressedCommand() {
        var registry = registry(new AtomicInteger());
        try (var interceptor = new GameCommandFrameInterceptor(
                registry,
                null,
                1024,
                MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10))) {
            var frame = commandFrame(0x01, "spawn");

            var result = interceptor.intercept(UnpooledByteBufAllocator.DEFAULT, frame, false, -1, source());

            assertTrue(result.forward());
            assertEquals(frame.readableBytes(), result.message().readableBytes());
            result.message().release();
        }
    }

    @Test
    void suppressesRegisteredCompressedCommand() {
        var executions = new AtomicInteger();
        var registry = registry(executions);
        try (var codec = new MinecraftCompressionCodec();
             var interceptor = new GameCommandFrameInterceptor(registry, null, 1024)) {
            var packet = commandPacket(0x04, "server survival-2");
            var frame = codec.encodeFrame(UnpooledByteBufAllocator.DEFAULT, packet, 1);
            packet.release();

            var result = interceptor.intercept(UnpooledByteBufAllocator.DEFAULT, frame, true, 1, source());

            assertFalse(result.forward());
            assertEquals(1, executions.get());
            frame.release();
        }
    }

    private static DefaultCommandRegistry registry(AtomicInteger executions) {
        var registry = new DefaultCommandRegistry();
        registry.register(new CommandSpec("server", List.of(), "", "", context -> {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(CommandResult.ok());
        }));
        return registry;
    }

    private static CommandSource source() {
        return new CommandSource() {
            @Override
            public String name() {
                return "Steve";
            }
        };
    }

    private static ByteBuf commandFrame(int packetId, String command) {
        var packet = commandPacket(packetId, command);
        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, packet.readableBytes());
        frame.writeBytes(packet);
        packet.release();
        return frame;
    }

    private static ByteBuf commandPacket(int packetId, String command) {
        var packet = Unpooled.buffer();
        MinecraftVarInts.write(packet, packetId);
        writeString(packet, command);
        packet.writeLong(0);
        packet.writeLong(0);
        return packet;
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static final class UnpooledByteBufAllocator {
        private static final io.netty.buffer.ByteBufAllocator DEFAULT = UnpooledByteBufAllocatorHolder.DEFAULT;
    }

    private static final class UnpooledByteBufAllocatorHolder {
        private static final io.netty.buffer.ByteBufAllocator DEFAULT = io.netty.buffer.ByteBufAllocator.DEFAULT;
    }
}

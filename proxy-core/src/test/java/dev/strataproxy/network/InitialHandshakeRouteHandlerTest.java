package dev.strataproxy.network;

import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerHealth;
import dev.strataproxy.api.server.ServerLoad;
import dev.strataproxy.observability.ProxyMetrics;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class InitialHandshakeRouteHandlerTest {
    @Test
    void recordsMalformedInitialFrame() {
        var metrics = new ProxyMetrics();
        var channel = new EmbeddedChannel(new InitialHandshakeRouteHandler(
                (handshake, remoteAddress) -> Optional.empty(),
                metrics,
                NetworkTuning.defaults()));

        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0x7F
        })));

        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.failedRoutes());
    }

    @Test
    void recordsMissingRouteForValidHandshake() {
        var metrics = new ProxyMetrics();
        var channel = new EmbeddedChannel(new InitialHandshakeRouteHandler(
                (handshake, remoteAddress) -> Optional.empty(),
                metrics,
                NetworkTuning.defaults()));

        assertFalse(channel.writeInbound(handshakeFrame(763, "play.example.net", 25565, 2)));

        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.failedRoutes());
    }

    @Test
    void rejectsOversizedPendingBytesAfterHandshakeBeforeBackendConnect() {
        var metrics = new ProxyMetrics();
        var tuning = new NetworkTuning(64, 1_000, 16, 64, 100, 100, 5_000);
        var selected = server("lobby-1");
        var channel = new EmbeddedChannel(new InitialHandshakeRouteHandler(
                (handshake, remoteAddress) -> Optional.of(selected),
                metrics,
                tuning));
        var input = Unpooled.buffer();
        input.writeBytes(handshakeFrame(763, "play.example.net", 25565, 2));
        input.writeZero(65);

        assertFalse(channel.writeInbound(input));

        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.failedRoutes());
        assertEquals(0, snapshot.backendConnectFailures());
        assertEquals(0, snapshot.routedConnections());
    }

    private static io.netty.buffer.ByteBuf handshakeFrame(int protocol, String host, int port, int nextState) {
        var payload = Unpooled.buffer();
        writeVarInt(payload, 0);
        writeVarInt(payload, protocol);
        writeString(payload, host);
        payload.writeShort(port);
        writeVarInt(payload, nextState);

        var frame = Unpooled.buffer();
        writeVarInt(frame, payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    private static void writeString(io.netty.buffer.ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeVarInt(io.netty.buffer.ByteBuf output, int value) {
        var current = value;
        do {
            var temp = current & 0x7F;
            current >>>= 7;
            if (current != 0) {
                temp |= 0x80;
            }
            output.writeByte(temp);
        } while (current != 0);
    }

    private static RegisteredServer server(String name) {
        return new TestRegisteredServer(
                new ServerDescriptor(
                        name,
                        new InetSocketAddress("127.0.0.1", 25565),
                        Set.of("lobby"),
                        Set.of(),
                        new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                        100,
                        100,
                        120,
                        false,
                        Map.of()),
                ServerHealth.up(1),
                new ServerLoad(0, 100, 120, 0, 0, 0, 0),
                false);
    }

    private record TestRegisteredServer(
            ServerDescriptor descriptor,
            ServerHealth health,
            ServerLoad load,
            boolean draining) implements RegisteredServer {
    }
}

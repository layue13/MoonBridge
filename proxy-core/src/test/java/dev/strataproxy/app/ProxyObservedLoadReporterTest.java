package dev.strataproxy.app;

import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.ServerCapability;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerLoad;
import dev.strataproxy.network.ProxyMetrics;
import dev.strataproxy.registry.InMemoryServerRegistry;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProxyObservedLoadReporterTest {
    @Test
    void flushesObservedTrafficRatesWithoutOverwritingConfiguredCapacity() {
        var registry = new InMemoryServerRegistry();
        registry.register(new ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("survival"),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                200,
                240,
                false,
                Map.of()));
        registry.updateLoad("survival-1", new ServerLoad(42, 200, 240, 0, 0, 0, 0.0d));
        var metrics = new ProxyMetrics();

        try (var reporter = new ProxyObservedLoadReporter(registry, metrics, Duration.ofSeconds(2))) {
            reporter.flushOnce();

            metrics.frontendToBackendBytes("survival-1", 512);
            metrics.backendToFrontendBytes("survival-1", 1024);
            metrics.eventLoopDelayNanos(2_500_000);
            reporter.flushOnce();
        }

        var load = registry.get("survival-1").orElseThrow().load();
        assertEquals(0, load.players());
        assertEquals(200, load.softCapacity());
        assertEquals(240, load.hardCapacity());
        assertEquals(256, load.inboundBytesPerSecond());
        assertEquals(512, load.outboundBytesPerSecond());
        assertEquals(0, load.packetsPerSecond());
        assertEquals(2.5d, load.eventLoopDelayMillis());
    }

    @Test
    void reportsActiveBackendConnectionsAsPlayersAcrossCloseAndTransfer() {
        var registry = new InMemoryServerRegistry();
        registry.register(server("survival-1"));
        registry.register(server("survival-2"));
        var metrics = new ProxyMetrics();

        try (var reporter = new ProxyObservedLoadReporter(registry, metrics, Duration.ofSeconds(2))) {
            metrics.serverConnectionOpened("survival-1");
            metrics.serverConnectionOpened("survival-1");
            reporter.flushOnce();
            assertEquals(2, registry.get("survival-1").orElseThrow().load().players());

            // A backend transfer closes the old backend and opens the new one.
            metrics.serverConnectionClosed("survival-1");
            metrics.serverConnectionOpened("survival-2");
            reporter.flushOnce();
            assertEquals(1, registry.get("survival-1").orElseThrow().load().players());
            assertEquals(1, registry.get("survival-2").orElseThrow().load().players());

            metrics.serverConnectionClosed("survival-1");
            reporter.flushOnce();
            assertEquals(0, registry.get("survival-1").orElseThrow().load().players());
        }
    }

    private static ServerDescriptor server(String name) {
        return new ServerDescriptor(
                name,
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("survival"),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                200,
                240,
                false,
                Map.of());
    }
}

package dev.strataproxy.plugins.dns;

import dev.strataproxy.api.Players;
import dev.strataproxy.api.PluginContext;
import dev.strataproxy.api.ServerDefinition;
import dev.strataproxy.api.ServerRegistration;
import dev.strataproxy.api.ServerView;
import dev.strataproxy.api.Servers;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsDiscoveryPluginTest {
    @Test
    void reconcilesAddressChangesAndCleansUpOnDisable() throws Exception {
        InetAddress firstAddress = address(10, 1, 2, 3);
        InetAddress secondAddress = address(10, 1, 2, 4);
        InetAddress thirdAddress = address(10, 1, 2, 5);
        InetAddress ipv6Address = InetAddress.getByAddress(new byte[]{
                0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 1});
        AtomicReference<InetAddress[]> answer = new AtomicReference<>(
                new InetAddress[]{firstAddress, firstAddress, secondAddress, ipv6Address});
        FakeServers servers = new FakeServers();
        DnsDiscoveryPlugin plugin = new DnsDiscoveryPlugin(host -> answer.get());
        plugin.onLoad(context(servers, Map.of(
                "host", "backend.example",
                "port", "25570",
                "namePrefix", "forge",
                "capacity", "48",
                "refreshSeconds", "1")));

        plugin.onEnable();
        await(() -> servers.definitions.size() == 3);
        String firstName = name("forge-", firstAddress);
        String secondName = name("forge-", secondAddress);
        String thirdName = name("forge-", thirdAddress);
        String ipv6Name = name("forge-", ipv6Address);
        assertEquals("tcp://10.1.2.3:25570", servers.definitions.get(firstName).address().toString());
        assertEquals("tcp://10.1.2.4:25570", servers.definitions.get(secondName).address().toString());
        assertEquals("tcp://[2001:db8:0:0:0:0:0:1]:25570", servers.definitions.get(ipv6Name).address().toString());
        assertEquals(48, servers.definitions.get(ipv6Name).capacity());
        assertEquals(3, servers.registerCalls.get());

        answer.set(new InetAddress[]{thirdAddress, secondAddress, ipv6Address});
        await(() -> servers.definitions.size() == 3 && servers.definitions.containsKey(thirdName)
                && !servers.definitions.containsKey(firstName));
        assertEquals("tcp://10.1.2.4:25570", servers.definitions.get(secondName).address().toString(),
                "a retained address must retain its backend identity");
        assertEquals(4, servers.registerCalls.get(), "only the newly discovered address should register");
        assertEquals(0, servers.updateCalls.get(), "stable retained addresses should not be reassigned");
        assertEquals(1, servers.unregisterCalls.get(), "only the removed address should unregister");

        plugin.onDisable();
        assertTrue(servers.definitions.isEmpty());
        assertEquals(4, servers.unregisterCalls.get());
        plugin.onDisable();
        assertEquals(4, servers.unregisterCalls.get(), "disable should be idempotent");
    }

    @Test
    void retainsRegistrationsAcrossResolutionFailureThenRemovesOnSuccessfulEmptyAnswer() throws Exception {
        InetAddress ip = address(192, 0, 2, 8);
        AtomicReference<InetAddress[]> answer = new AtomicReference<>(new InetAddress[]{ip});
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch failureSeen = new CountDownLatch(1);
        DnsDiscoveryPlugin plugin = new DnsDiscoveryPlugin(host -> {
            Exception current = failure.get();
            if (current != null) {
                failureSeen.countDown();
                throw current;
            }
            return answer.get();
        });
        FakeServers servers = new FakeServers();
        plugin.onLoad(context(servers, Map.of("host", "pool.example", "refreshSeconds", "1")));
        plugin.onEnable();
        await(() -> servers.definitions.size() == 1);

        failure.set(new IOException("temporary resolver failure"));
        assertTrue(failureSeen.await(3, TimeUnit.SECONDS), "scheduled lookup should observe injected failure");
        assertEquals(1, servers.definitions.size(), "lookup failure must not remove a live registration");

        failure.set(null);
        answer.set(new InetAddress[0]);
        await(servers.definitions::isEmpty);
        assertEquals(1, servers.unregisterCalls.get());
        plugin.onDisable();
        assertTrue(servers.definitions.isEmpty());
    }

    @Test
    void rejectsInvalidSettingsBeforeStartingWorker() {
        DnsDiscoveryPlugin missingHost = new DnsDiscoveryPlugin(host -> new InetAddress[0]);
        assertThrows(IllegalArgumentException.class,
                () -> missingHost.onLoad(context(new FakeServers(), Map.of("port", "25565"))));

        DnsDiscoveryPlugin invalidPort = new DnsDiscoveryPlugin(host -> new InetAddress[0]);
        assertThrows(IllegalArgumentException.class,
                () -> invalidPort.onLoad(context(new FakeServers(), Map.of("host", "pool.example", "port", "0"))));

        DnsDiscoveryPlugin invalidPrefix = new DnsDiscoveryPlugin(host -> new InetAddress[0]);
        assertThrows(IllegalArgumentException.class,
                () -> invalidPrefix.onLoad(context(new FakeServers(),
                        Map.of("host", "pool.example", "namePrefix", "bad prefix"))));
    }

    @Test
    void serviceLoaderDescriptorNamesPluginImplementation() throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "META-INF/services/dev.strataproxy.api.Plugin")) {
            assertNotNull(stream);
            String descriptor = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(descriptor.lines().anyMatch(DnsDiscoveryPlugin.class.getName()::equals));
        }
    }

    private static PluginContext context(FakeServers servers, Map<String, String> settings) {
        return new PluginContext() {
            @Override
            public Players players() {
                return null;
            }

            @Override
            public Servers servers() {
                return servers;
            }

            @Override
            public org.slf4j.Logger logger() {
                return LoggerFactory.getLogger("dns-discovery-test");
            }

            @Override
            public Map<String, String> settings() {
                return settings;
            }
        };
    }

    private static InetAddress address(int a, int b, int c, int d) throws Exception {
        return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
    }

    private static String name(String prefix, InetAddress address) {
        return prefix + java.util.HexFormat.of().formatHex(address.getAddress());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                assertTrue(condition.getAsBoolean(), "condition not met before timeout");
                return;
            }
            Thread.sleep(10);
        }
    }

    private static final class FakeServers implements Servers {
        private final Map<String, ServerDefinition> definitions = new ConcurrentHashMap<>();
        private final AtomicInteger registerCalls = new AtomicInteger();
        private final AtomicInteger updateCalls = new AtomicInteger();
        private final AtomicInteger unregisterCalls = new AtomicInteger();

        @Override
        public Optional<ServerView> find(String backendName) {
            return Optional.empty();
        }

        @Override
        public List<ServerView> all() {
            return new ArrayList<>();
        }

        @Override
        public ServerRegistration register(ServerDefinition definition) {
            if (definitions.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalStateException("duplicate backend " + definition.name());
            }
            registerCalls.incrementAndGet();
            return new ServerRegistration() {
                private boolean active = true;

                @Override
                public void update(ServerDefinition replacement) {
                    assertTrue(active);
                    assertEquals(definition.name(), replacement.name());
                    definitions.put(replacement.name(), replacement);
                    updateCalls.incrementAndGet();
                }

                @Override
                public void unregister() {
                    assertTrue(active);
                    active = false;
                    definitions.remove(definition.name());
                    unregisterCalls.incrementAndGet();
                }
            };
        }
    }
}

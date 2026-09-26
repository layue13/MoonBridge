package dev.strataproxy.plugins.dns;

import dev.strataproxy.api.Players;
import dev.strataproxy.api.PluginContext;
import dev.strataproxy.api.ServerDefinition;
import dev.strataproxy.api.ServerRegistration;
import dev.strataproxy.api.ServerView;
import dev.strataproxy.api.Servers;
import io.netty.resolver.dns.DnsServerAddresses;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals(3, servers.registerCalls.get());
        assertEquals(List.of(firstName, secondName, ipv6Name), servers.registrationOrder);

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
    void repeatedMissingDnsAnswerExpiresStaleRegistrationAndRecoveryRegistersAgain() throws Exception {
        InetAddress first = address(192, 0, 2, 20);
        InetAddress replacement = address(192, 0, 2, 21);
        AtomicReference<InetAddress[]> answer = new AtomicReference<>(new InetAddress[]{first});
        AtomicInteger misses = new AtomicInteger();
        DnsDiscoveryPlugin plugin = new DnsDiscoveryPlugin(host -> {
            InetAddress[] current = answer.get();
            if (current != null) return current;
            misses.incrementAndGet();
            throw new UnknownHostException(host);
        });
        FakeServers servers = new FakeServers();
        plugin.onLoad(context(servers, Map.of("host", "pool.example", "refreshSeconds", "1")));
        try {
            plugin.onEnable();
            await(() -> servers.definitions.containsKey(name("dns-pool-example-", first)));
            answer.set(null);
            await(() -> misses.get() >= 1);
            assertEquals(1, servers.definitions.size(), "one transient lookup miss should retain the backend");
            await(servers.definitions::isEmpty);
            assertTrue(misses.get() >= 3);
            assertEquals(1, servers.unregisterCalls.get());

            answer.set(new InetAddress[]{replacement});
            await(() -> servers.definitions.containsKey(name("dns-pool-example-", replacement)));
            assertEquals(2, servers.registerCalls.get());
        } finally {
            plugin.onDisable();
        }
    }

    @Test
    void resolverKeepsFailedAddressFamilyUntilItsThirdMiss() throws Exception {
        InetAddress firstV4 = address(192, 0, 2, 10);
        InetAddress secondV4 = address(192, 0, 2, 11);
        InetAddress firstV6 = InetAddress.getByAddress(new byte[]{
                0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 10});
        InetAddress secondV6 = InetAddress.getByAddress(new byte[]{
                0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 11});
        AtomicReference<byte[]> v4 = new AtomicReference<>(firstV4.getAddress());
        AtomicReference<byte[]> v6 = new AtomicReference<>(firstV6.getAddress());
        AtomicBoolean dropV6 = new AtomicBoolean();
        AtomicReference<Throwable> dnsFailure = new AtomicReference<>();
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            Thread dnsThread = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        DatagramPacket query = new DatagramPacket(new byte[4096], 4096);
                        socket.receive(query);
                        if (dropV6.get() && dnsQuestionType(query) == 28) continue;
                        byte[] response = dnsAnswer(query, v4.get(), v6.get());
                        socket.send(new DatagramPacket(response, response.length, query.getSocketAddress()));
                    } catch (java.net.SocketException closed) {
                        if (!socket.isClosed()) dnsFailure.set(closed);
                        return;
                    } catch (Throwable failure) {
                        dnsFailure.set(failure);
                        return;
                    }
                }
            }, "fake-dns-answer-server");
            dnsThread.setDaemon(true);
            dnsThread.start();
            InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", socket.getLocalPort());
            try (var resolver = new DnsDiscoveryPlugin.RefreshingAddressResolver(
                    hostname -> DnsServerAddresses.singleton(endpoint).stream())) {
                assertEquals(Set.of(firstV4, firstV6),
                        Set.copyOf(Arrays.asList(resolver.resolve("backend.dynamic.test"))));
                v4.set(secondV4.getAddress());
                v6.set(secondV6.getAddress());
                assertEquals(Set.of(secondV4, secondV6),
                        Set.copyOf(Arrays.asList(resolver.resolve("backend.dynamic.test"))));
                v6.set(null);
                assertEquals(Set.of(secondV4),
                        Set.copyOf(Arrays.asList(resolver.resolve("backend.dynamic.test"))));
                v6.set(secondV6.getAddress());
                v4.set(null);
                assertEquals(Set.of(secondV6),
                        Set.copyOf(Arrays.asList(resolver.resolve("backend.dynamic.test"))));
                v4.set(secondV4.getAddress());
                dropV6.set(true);
                assertEquals(Set.of(secondV4, secondV6),
                        Set.copyOf(Arrays.asList(resolver.resolve("backend.dynamic.test"))));
                assertEquals(Set.of(secondV4, secondV6),
                        Set.copyOf(Arrays.asList(resolver.resolve("backend.dynamic.test"))));
                assertEquals(Set.of(secondV4),
                        Set.copyOf(Arrays.asList(resolver.resolve("backend.dynamic.test"))),
                        "a timed-out AAAA query should retire only its stale address family after three misses");
                dropV6.set(false);
                assertEquals(Set.of(secondV4, secondV6),
                        Set.copyOf(Arrays.asList(resolver.resolve("backend.dynamic.test"))));
                v4.set(null);
                dropV6.set(true);
                assertEquals(Set.of(secondV6),
                        Set.copyOf(Arrays.asList(resolver.resolve("backend.dynamic.test"))));
                assertEquals(Set.of(secondV6),
                        Set.copyOf(Arrays.asList(resolver.resolve("backend.dynamic.test"))));
                assertEquals(0, resolver.resolve("backend.dynamic.test").length,
                        "the last stale address should be removed on its third missed query");
            }
            assertNull(dnsFailure.get());
        }
    }

    private static byte[] dnsAnswer(DatagramPacket query, byte[] v4, byte[] v6) throws IOException {
        byte[] request = query.getData();
        int length = query.getLength();
        int offset = 12;
        while (offset < length && request[offset] != 0) offset += 1 + (request[offset] & 0xff);
        if (offset + 5 > length) throw new IOException("truncated DNS question");
        int type = dnsQuestionType(query);
        int questionLength = offset + 5 - 12;
        byte[] address = type == 1 ? v4 : type == 28 ? v6 : null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        output.write(request, 0, 2); // Transaction ID.
        output.writeShort(0x8180); // Standard successful response.
        output.writeShort(1);
        output.writeShort(address == null ? 0 : 1);
        output.writeInt(0);
        output.write(request, 12, questionLength);
        if (address != null) {
            output.writeShort(0xc00c); // Name pointer to the question.
            output.writeShort(type);
            output.writeShort(1); // IN class.
            output.writeInt(0); // TTL: a new DNS answer must be consulted on refresh.
            output.writeShort(address.length);
            output.write(address);
        }
        return bytes.toByteArray();
    }

    private static int dnsQuestionType(DatagramPacket query) throws IOException {
        byte[] request = query.getData();
        int length = query.getLength();
        int offset = 12;
        while (offset < length && request[offset] != 0) offset += 1 + (request[offset] & 0xff);
        if (offset + 5 > length) throw new IOException("truncated DNS question");
        return ((request[offset + 1] & 0xff) << 8) | (request[offset + 2] & 0xff);
    }

    @Test
    void rejectsInvalidSettingsBeforeStartingWorker() {
        DnsDiscoveryPlugin obsoleteCapacity = new DnsDiscoveryPlugin(host -> new InetAddress[0]);
        assertThrows(IllegalArgumentException.class,
                () -> obsoleteCapacity.onLoad(context(new FakeServers(),
                        Map.of("host", "pool.example", "capacity", "10"))));

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

            @Override public dev.strataproxy.api.Commands commands() {
                return (name, handler) -> { throw new UnsupportedOperationException(); };
            }
            @Override public dev.strataproxy.api.event.Events events() { throw new UnsupportedOperationException(); }

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
        private final List<String> registrationOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
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
            registrationOrder.add(definition.name());
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

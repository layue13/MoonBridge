package dev.strataproxy.query;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Command(
        name = "strataproxy-query",
        mixinStandardHelpOptions = true,
        version = "strataproxy-query 0.1",
        description = "Minecraft status and lightweight connection-load probe tool.",
        subcommands = {
                StrataProxyQueryCli.StatusCommand.class,
                StrataProxyQueryCli.IdleLoadCommand.class,
                StrataProxyQueryCli.HandshakeLoadCommand.class,
                StrataProxyQueryCli.RouteStormCommand.class,
                StrataProxyQueryCli.TrafficLoadCommand.class,
                StrataProxyQueryCli.CompressionRewriteLoadCommand.class,
                StrataProxyQueryCli.LoadSuiteCommand.class,
                StrataProxyQueryCli.SlowSinkCommand.class
        })
public final class StrataProxyQueryCli implements Callable<Integer> {
    @Option(names = "--host", defaultValue = "127.0.0.1", description = "Target host.")
    private String host;

    @Option(names = "--port", defaultValue = "25577", description = "Target port.")
    private int port;

    @Option(names = "--timeout-ms", defaultValue = "5000", description = "Connect/read timeout in milliseconds.")
    private int timeoutMillis;

    public static void main(String[] args) {
        System.exit(new CommandLine(new StrataProxyQueryCli()).execute(args));
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    InetSocketAddress address() {
        return new InetSocketAddress(host, port);
    }

    int timeoutMillis() {
        return timeoutMillis;
    }

    @Command(name = "status", description = "Run a Minecraft status ping and print the status JSON.")
    static final class StatusCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyQueryCli root;

        @Option(names = "--protocol", defaultValue = "763", description = "Minecraft protocol version.")
        private int protocolVersion;

        @Option(names = "--virtual-host", description = "Hostname to place in the Minecraft handshake.")
        private String virtualHost;

        @Override
        public Integer call() throws Exception {
            var address = root.address();
            var handshakeHost = virtualHost == null || virtualHost.isBlank() ? address.getHostString() : virtualHost;
            try (var socket = new Socket()) {
                socket.connect(address, root.timeoutMillis());
                socket.setSoTimeout(root.timeoutMillis());
                socket.getOutputStream().write(MinecraftQueryProtocol.statusHandshake(protocolVersion, handshakeHost, address.getPort()));
                socket.getOutputStream().write(MinecraftQueryProtocol.statusRequest());
                socket.getOutputStream().flush();
                System.out.println(MinecraftQueryProtocol.readStatusResponse(socket.getInputStream()));
                return 0;
            }
        }
    }

    @Command(name = "handshake-load", description = "Open connections, send Minecraft login handshakes, and probe route survivability.")
    static final class HandshakeLoadCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyQueryCli root;

        @Option(names = "--connections", defaultValue = "100", description = "Number of Minecraft handshakes to send.")
        private int connections;

        @Option(names = "--hold-ms", defaultValue = "5000", description = "How long to hold successful handshaken connections.")
        private long holdMillis;

        @Option(names = "--parallelism", defaultValue = "128", description = "Maximum concurrent connection attempts.")
        private int parallelism;

        @Option(names = "--settle-ms", defaultValue = "100", description = "Delay before probing whether handshaken sockets are still open.")
        private long settleMillis;

        @Option(names = "--probe-timeout-ms", defaultValue = "50", description = "Read timeout used to distinguish routed-open sockets from closed sockets.")
        private int probeTimeoutMillis;

        @Option(names = "--protocol", defaultValue = "763", description = "Minecraft protocol version.")
        private int protocolVersion;

        @Option(names = "--virtual-host", description = "Hostname to place in the Minecraft handshake.")
        private String virtualHost;

        @Option(names = "--fail-on-closed", description = "Exit with a non-zero code when any handshaken socket is closed during the probe.")
        private boolean failOnClosed;

        @Option(names = "--min-handshaken", defaultValue = "0", description = "Minimum successful handshakes required for exit code 0.")
        private int minHandshaken;

        @Option(names = "--min-alive", defaultValue = "0", description = "Minimum alive sockets required for exit code 0.")
        private int minAlive;

        @Option(names = "--max-closed", defaultValue = "2147483647", description = "Maximum closed sockets allowed for exit code 0.")
        private int maxClosed;

        @Option(names = "--max-failed", defaultValue = "0", description = "Maximum failed connection attempts allowed for exit code 0.")
        private int maxFailed;

        @Option(names = "--min-handshake-rate", defaultValue = "0.0", description = "Minimum handshakes per second required for exit code 0.")
        private double minHandshakeRate;

        @Option(names = "--json", description = "Print a machine-readable JSON result.")
        private boolean json;

        @Override
        public Integer call() throws Exception {
            validateLoadOptions(connections, parallelism, settleMillis, probeTimeoutMillis);
            validateAcceptance("min-handshaken", minHandshaken);
            validateAcceptance("min-alive", minAlive);
            validateAcceptance("max-closed", maxClosed);
            validateAcceptance("max-failed", maxFailed);
            validateAcceptance("min-handshake-rate", minHandshakeRate);
            var startedAt = Instant.now();
            var sockets = Collections.synchronizedList(new ArrayList<Socket>(connections));
            var attempted = new AtomicInteger();
            var connected = new AtomicInteger();
            var handshaken = new AtomicInteger();
            var failed = new AtomicInteger();
            var alive = new AtomicInteger();
            var closed = new AtomicInteger();
            var address = root.address();
            var handshakeHost = virtualHost == null || virtualHost.isBlank() ? address.getHostString() : virtualHost;

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                var permits = new java.util.concurrent.Semaphore(parallelism);
                for (var i = 0; i < connections; i++) {
                    permits.acquire();
                    futures.add(executor.submit(() -> {
                        try {
                            attempted.incrementAndGet();
                            var socket = new Socket();
                            socket.connect(address, root.timeoutMillis());
                            connected.incrementAndGet();
                            socket.getOutputStream().write(MinecraftQueryProtocol.loginHandshake(protocolVersion, handshakeHost, address.getPort()));
                            socket.getOutputStream().flush();
                            sockets.add(socket);
                            handshaken.incrementAndGet();
                        } catch (IOException exception) {
                            failed.incrementAndGet();
                        } finally {
                            permits.release();
                        }
                    }));
                }
                for (var future : futures) {
                    future.get();
                }
                TimeUnit.MILLISECONDS.sleep(settleMillis);
                var probes = new ArrayList<java.util.concurrent.Future<?>>();
                synchronized (sockets) {
                    for (var socket : sockets) {
                        probes.add(executor.submit(() -> {
                            if (isSocketAlive(socket, probeTimeoutMillis)) {
                                alive.incrementAndGet();
                            } else {
                                closed.incrementAndGet();
                            }
                        }));
                    }
                }
                for (var probe : probes) {
                    probe.get();
                }
                TimeUnit.MILLISECONDS.sleep(Math.max(0, holdMillis));
            } finally {
                for (var socket : sockets) {
                    close(socket);
                }
            }

            var elapsedMillis = Math.max(1, Duration.between(startedAt, Instant.now()).toMillis());
            var handshakeRate = handshaken.get() * 1000.0d / elapsedMillis;
            var allowedClosed = failOnClosed ? 0 : maxClosed;
            var passed = failed.get() <= maxFailed
                    && handshaken.get() >= minHandshaken
                    && alive.get() >= minAlive
                    && closed.get() <= allowedClosed
                    && handshakeRate >= minHandshakeRate;
            if (json) {
                printJson("handshake-load", passed,
                        "attempted", attempted.get(),
                        "connected", connected.get(),
                        "handshaken", handshaken.get(),
                        "alive", alive.get(),
                        "closed", closed.get(),
                        "failed", failed.get(),
                        "heldMillis", Math.max(0, holdMillis),
                        "settleMillis", settleMillis,
                        "elapsedMillis", elapsedMillis,
                        "handshakeRatePerSecond", handshakeRate);
            } else {
                System.out.printf(
                        "attempted=%d connected=%d handshaken=%d alive=%d closed=%d failed=%d heldMillis=%d settleMillis=%d elapsedMillis=%d handshakeRatePerSecond=%.2f%n",
                        attempted.get(),
                        connected.get(),
                        handshaken.get(),
                        alive.get(),
                        closed.get(),
                        failed.get(),
                        Math.max(0, holdMillis),
                        settleMillis,
                        elapsedMillis,
                        handshakeRate);
            }
            return passed ? 0 : 1;
        }
    }

    @Command(name = "route-storm", description = "Send login handshakes across many virtual hosts to probe routing storm behavior.")
    static final class RouteStormCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyQueryCli root;

        @Option(names = "--connections", defaultValue = "1000", description = "Number of Minecraft handshakes to send.")
        private int connections;

        @Option(names = "--routes", defaultValue = "100", description = "Number of distinct virtual hosts to cycle through.")
        private int routes;

        @Option(names = "--virtual-host-template", defaultValue = "route-%d.example.net", description = "Virtual host template. Must contain one integer format placeholder.")
        private String virtualHostTemplate;

        @Option(names = "--hold-ms", defaultValue = "0", description = "How long to hold successful handshaken connections after probing.")
        private long holdMillis;

        @Option(names = "--parallelism", defaultValue = "256", description = "Maximum concurrent connection attempts.")
        private int parallelism;

        @Option(names = "--settle-ms", defaultValue = "100", description = "Delay before probing whether handshaken sockets are still open.")
        private long settleMillis;

        @Option(names = "--probe-timeout-ms", defaultValue = "50", description = "Read timeout used to distinguish routed-open sockets from closed sockets.")
        private int probeTimeoutMillis;

        @Option(names = "--protocol", defaultValue = "763", description = "Minecraft protocol version.")
        private int protocolVersion;

        @Option(names = "--fail-on-closed", description = "Exit with a non-zero code when any handshaken socket is closed during the probe.")
        private boolean failOnClosed;

        @Option(names = "--min-handshaken", defaultValue = "0", description = "Minimum successful handshakes required for exit code 0.")
        private int minHandshaken;

        @Option(names = "--min-alive", defaultValue = "0", description = "Minimum alive sockets required for exit code 0.")
        private int minAlive;

        @Option(names = "--min-routes-handshaken", defaultValue = "0", description = "Minimum distinct virtual hosts that must complete a handshake.")
        private int minRoutesHandshaken;

        @Option(names = "--max-closed", defaultValue = "2147483647", description = "Maximum closed sockets allowed for exit code 0.")
        private int maxClosed;

        @Option(names = "--max-failed", defaultValue = "0", description = "Maximum failed connection attempts allowed for exit code 0.")
        private int maxFailed;

        @Option(names = "--min-handshake-rate", defaultValue = "0.0", description = "Minimum handshakes per second required for exit code 0.")
        private double minHandshakeRate;

        @Option(names = "--min-route-rate", defaultValue = "0.0", description = "Minimum distinct handshaken routes per second required for exit code 0.")
        private double minRouteRate;

        @Option(names = "--json", description = "Print a machine-readable JSON result.")
        private boolean json;

        @Override
        public Integer call() throws Exception {
            validateLoadOptions(connections, parallelism, settleMillis, probeTimeoutMillis);
            if (routes <= 0) {
                throw new IllegalArgumentException("routes must be positive");
            }
            validateHostTemplate(virtualHostTemplate);
            validateAcceptance("min-handshaken", minHandshaken);
            validateAcceptance("min-alive", minAlive);
            validateAcceptance("min-routes-handshaken", minRoutesHandshaken);
            validateAcceptance("max-closed", maxClosed);
            validateAcceptance("max-failed", maxFailed);
            validateAcceptance("min-handshake-rate", minHandshakeRate);
            validateAcceptance("min-route-rate", minRouteRate);

            var startedAt = Instant.now();
            var sockets = Collections.synchronizedList(new ArrayList<Socket>(connections));
            Set<String> attemptedRoutes = ConcurrentHashMap.newKeySet();
            Set<String> handshakenRoutes = ConcurrentHashMap.newKeySet();
            var attempted = new AtomicInteger();
            var connected = new AtomicInteger();
            var handshaken = new AtomicInteger();
            var failed = new AtomicInteger();
            var alive = new AtomicInteger();
            var closed = new AtomicInteger();
            var address = root.address();

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                var permits = new java.util.concurrent.Semaphore(parallelism);
                for (var i = 0; i < connections; i++) {
                    var routeIndex = Math.floorMod(i, routes);
                    var handshakeHost = routeHost(virtualHostTemplate, routeIndex);
                    attemptedRoutes.add(handshakeHost);
                    permits.acquire();
                    futures.add(executor.submit(() -> {
                        try {
                            attempted.incrementAndGet();
                            var socket = new Socket();
                            socket.connect(address, root.timeoutMillis());
                            connected.incrementAndGet();
                            socket.getOutputStream().write(MinecraftQueryProtocol.loginHandshake(protocolVersion, handshakeHost, address.getPort()));
                            socket.getOutputStream().flush();
                            sockets.add(socket);
                            handshaken.incrementAndGet();
                            handshakenRoutes.add(handshakeHost);
                        } catch (IOException exception) {
                            failed.incrementAndGet();
                        } finally {
                            permits.release();
                        }
                    }));
                }
                for (var future : futures) {
                    future.get();
                }
                TimeUnit.MILLISECONDS.sleep(settleMillis);
                var probes = new ArrayList<java.util.concurrent.Future<?>>();
                synchronized (sockets) {
                    for (var socket : sockets) {
                        probes.add(executor.submit(() -> {
                            if (isSocketAlive(socket, probeTimeoutMillis)) {
                                alive.incrementAndGet();
                            } else {
                                closed.incrementAndGet();
                            }
                        }));
                    }
                }
                for (var probe : probes) {
                    probe.get();
                }
                TimeUnit.MILLISECONDS.sleep(Math.max(0, holdMillis));
            } finally {
                for (var socket : sockets) {
                    close(socket);
                }
            }

            var elapsedMillis = Math.max(1, Duration.between(startedAt, Instant.now()).toMillis());
            var handshakeRate = handshaken.get() * 1000.0d / elapsedMillis;
            var routeRate = handshakenRoutes.size() * 1000.0d / elapsedMillis;
            var allowedClosed = failOnClosed ? 0 : maxClosed;
            var passed = failed.get() <= maxFailed
                    && handshaken.get() >= minHandshaken
                    && alive.get() >= minAlive
                    && handshakenRoutes.size() >= minRoutesHandshaken
                    && closed.get() <= allowedClosed
                    && handshakeRate >= minHandshakeRate
                    && routeRate >= minRouteRate;
            if (json) {
                printJson("route-storm", passed,
                        "attempted", attempted.get(),
                        "connected", connected.get(),
                        "handshaken", handshaken.get(),
                        "alive", alive.get(),
                        "closed", closed.get(),
                        "failed", failed.get(),
                        "routesConfigured", routes,
                        "routesAttempted", attemptedRoutes.size(),
                        "routesHandshaken", handshakenRoutes.size(),
                        "heldMillis", Math.max(0, holdMillis),
                        "settleMillis", settleMillis,
                        "elapsedMillis", elapsedMillis,
                        "handshakeRatePerSecond", handshakeRate,
                        "routeRatePerSecond", routeRate);
            } else {
                System.out.printf(
                        "attempted=%d connected=%d handshaken=%d alive=%d closed=%d failed=%d routesConfigured=%d routesAttempted=%d routesHandshaken=%d heldMillis=%d settleMillis=%d elapsedMillis=%d handshakeRatePerSecond=%.2f routeRatePerSecond=%.2f%n",
                        attempted.get(),
                        connected.get(),
                        handshaken.get(),
                        alive.get(),
                        closed.get(),
                        failed.get(),
                        routes,
                        attemptedRoutes.size(),
                        handshakenRoutes.size(),
                        Math.max(0, holdMillis),
                        settleMillis,
                        elapsedMillis,
                        handshakeRate,
                        routeRate);
            }
            return passed ? 0 : 1;
        }
    }

    @Command(name = "traffic-load", description = "Send Minecraft-framed traffic after login handshakes to probe relay throughput.")
    static final class TrafficLoadCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyQueryCli root;

        @Option(names = "--connections", defaultValue = "100", description = "Number of concurrent handshaken connections.")
        private int connections;

        @Option(names = "--parallelism", defaultValue = "128", description = "Maximum concurrent connection workers.")
        private int parallelism;

        @Option(names = "--protocol", defaultValue = "763", description = "Minecraft protocol version.")
        private int protocolVersion;

        @Option(names = "--virtual-host", description = "Hostname to place in the Minecraft handshake.")
        private String virtualHost;

        @Option(names = "--login-start", description = "Send a Login Start packet with a generated player name before traffic frames.")
        private boolean loginStart;

        @Option(names = "--player-template", defaultValue = "load%05d", description = "Player name template for --login-start. Must contain one integer format placeholder and fit in 16 characters.")
        private String playerTemplate;

        @Option(names = "--packets-per-connection", defaultValue = "100", description = "Number of packet frames each connection sends.")
        private int packetsPerConnection;

        @Option(names = "--payload-bytes", defaultValue = "32", description = "Zero-filled payload bytes after the packet id.")
        private int payloadBytes;

        @Option(names = "--packet-id", defaultValue = "1", description = "Packet id to use for generated frames.")
        private int packetId;

        @Option(names = "--inter-packet-delay-ms", defaultValue = "0", description = "Optional delay between generated packets on each connection.")
        private long interPacketDelayMillis;

        @Option(names = "--hold-ms", defaultValue = "0", description = "How long to hold connections after sending traffic.")
        private long holdMillis;

        @Option(names = "--min-handshaken", defaultValue = "0", description = "Minimum successful handshakes required for exit code 0.")
        private int minHandshaken;

        @Option(names = "--min-packets-sent", defaultValue = "0", description = "Minimum sent packet frames required for exit code 0.")
        private long minPacketsSent;

        @Option(names = "--min-bytes-sent", defaultValue = "0", description = "Minimum sent bytes required for exit code 0.")
        private long minBytesSent;

        @Option(names = "--max-failed", defaultValue = "0", description = "Maximum failed connection attempts allowed for exit code 0.")
        private int maxFailed;

        @Option(names = "--min-packet-rate", defaultValue = "0.0", description = "Minimum packets per second required for exit code 0.")
        private double minPacketRate;

        @Option(names = "--min-byte-rate", defaultValue = "0.0", description = "Minimum bytes per second required for exit code 0.")
        private double minByteRate;

        @Option(names = "--measure-echo-latency", description = "Read one echoed frame after every sent packet and report latency percentiles.")
        private boolean measureEchoLatency;

        @Option(names = "--max-p99-latency-ms", defaultValue = "0.0", description = "Maximum p99 echo latency in milliseconds for exit code 0. Use 0 to disable.")
        private double maxP99LatencyMillis;

        @Option(names = "--json", description = "Print a machine-readable JSON result.")
        private boolean json;

        @Override
        public Integer call() throws Exception {
            validateLoadOptions(connections, parallelism, 0, 1);
            if (packetsPerConnection < 0) {
                throw new IllegalArgumentException("packets-per-connection must be non-negative");
            }
            if (payloadBytes < 0) {
                throw new IllegalArgumentException("payload-bytes must be non-negative");
            }
            if (interPacketDelayMillis < 0) {
                throw new IllegalArgumentException("inter-packet-delay-ms must not be negative");
            }
            if (loginStart) {
                validatePlayerTemplate(playerTemplate, connections);
            }
            validateAcceptance("min-handshaken", minHandshaken);
            validateAcceptance("min-packets-sent", minPacketsSent);
            validateAcceptance("min-bytes-sent", minBytesSent);
            validateAcceptance("max-failed", maxFailed);
            validateAcceptance("min-packet-rate", minPacketRate);
            validateAcceptance("min-byte-rate", minByteRate);
            validateAcceptance("max-p99-latency-ms", maxP99LatencyMillis);
            var startedAt = Instant.now();
            var sockets = Collections.synchronizedList(new ArrayList<Socket>(connections));
            var attempted = new AtomicInteger();
            var connected = new AtomicInteger();
            var handshaken = new AtomicInteger();
            var failed = new AtomicInteger();
            var loginStartsSent = new AtomicInteger();
            var loginStartBytesSent = new java.util.concurrent.atomic.LongAdder();
            var packetsSent = new java.util.concurrent.atomic.LongAdder();
            var bytesSent = new java.util.concurrent.atomic.LongAdder();
            var latencySamples = Collections.synchronizedList(new ArrayList<Long>());
            var address = root.address();
            var handshakeHost = virtualHost == null || virtualHost.isBlank() ? address.getHostString() : virtualHost;
            var handshake = MinecraftQueryProtocol.loginHandshake(protocolVersion, handshakeHost, address.getPort());
            var packet = MinecraftQueryProtocol.rawPacketFrame(packetId, payloadBytes);

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                var permits = new java.util.concurrent.Semaphore(parallelism);
                for (var i = 0; i < connections; i++) {
                    var connectionIndex = i;
                    permits.acquire();
                    futures.add(executor.submit(() -> {
                        try {
                            attempted.incrementAndGet();
                            var socket = new Socket();
                            socket.connect(address, root.timeoutMillis());
                            connected.incrementAndGet();
                            var output = socket.getOutputStream();
                            output.write(handshake);
                            output.flush();
                            sockets.add(socket);
                            handshaken.incrementAndGet();
                            if (loginStart) {
                                var loginStartFrame = MinecraftQueryProtocol.loginStart(playerName(playerTemplate, connectionIndex));
                                output.write(loginStartFrame);
                                loginStartsSent.incrementAndGet();
                                loginStartBytesSent.add(loginStartFrame.length);
                            }
                            for (var packetIndex = 0; packetIndex < packetsPerConnection; packetIndex++) {
                                var startedPacketAt = measureEchoLatency ? System.nanoTime() : 0L;
                                output.write(packet);
                                packetsSent.increment();
                                bytesSent.add(packet.length);
                                if (measureEchoLatency) {
                                    output.flush();
                                    var echoed = socket.getInputStream().readNBytes(packet.length);
                                    if (echoed.length != packet.length) {
                                        failed.incrementAndGet();
                                        break;
                                    }
                                    latencySamples.add(System.nanoTime() - startedPacketAt);
                                }
                                if (interPacketDelayMillis > 0) {
                                    TimeUnit.MILLISECONDS.sleep(interPacketDelayMillis);
                                }
                            }
                            output.flush();
                        } catch (IOException exception) {
                            failed.incrementAndGet();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            failed.incrementAndGet();
                        } finally {
                            permits.release();
                        }
                    }));
                }
                for (var future : futures) {
                    future.get();
                }
                TimeUnit.MILLISECONDS.sleep(Math.max(0, holdMillis));
            } finally {
                for (var socket : sockets) {
                    close(socket);
                }
            }

            var elapsedMillis = Math.max(1, Duration.between(startedAt, Instant.now()).toMillis());
            var packetRate = packetsSent.sum() * 1000.0d / elapsedMillis;
            var byteRate = bytesSent.sum() * 1000.0d / elapsedMillis;
            var latency = LatencySummary.from(latencySamples);
            var latencyPassed = maxP99LatencyMillis <= 0.0d || (latency.samples() > 0 && latency.p99Millis() <= maxP99LatencyMillis);
            var passed = failed.get() <= maxFailed
                    && handshaken.get() >= minHandshaken
                    && packetsSent.sum() >= minPacketsSent
                    && bytesSent.sum() >= minBytesSent
                    && packetRate >= minPacketRate
                    && byteRate >= minByteRate
                    && latencyPassed;
            if (json) {
                printJson("traffic-load", passed,
                        "attempted", attempted.get(),
                        "connected", connected.get(),
                        "handshaken", handshaken.get(),
                        "loginStartsSent", loginStartsSent.get(),
                        "loginStartBytesSent", loginStartBytesSent.sum(),
                        "packetsSent", packetsSent.sum(),
                        "bytesSent", bytesSent.sum(),
                        "failed", failed.get(),
                        "heldMillis", Math.max(0, holdMillis),
                        "elapsedMillis", elapsedMillis,
                        "packetRatePerSecond", packetRate,
                        "byteRatePerSecond", byteRate,
                        "latencySamples", latency.samples(),
                        "p50LatencyMillis", latency.p50Millis(),
                        "p95LatencyMillis", latency.p95Millis(),
                        "p99LatencyMillis", latency.p99Millis(),
                        "maxLatencyMillis", latency.maxMillis());
            } else {
                System.out.printf(
                        "attempted=%d connected=%d handshaken=%d loginStartsSent=%d loginStartBytesSent=%d packetsSent=%d bytesSent=%d failed=%d heldMillis=%d elapsedMillis=%d packetRatePerSecond=%.2f byteRatePerSecond=%.2f latencySamples=%d p50LatencyMillis=%.3f p95LatencyMillis=%.3f p99LatencyMillis=%.3f maxLatencyMillis=%.3f%n",
                        attempted.get(),
                        connected.get(),
                        handshaken.get(),
                        loginStartsSent.get(),
                        loginStartBytesSent.sum(),
                        packetsSent.sum(),
                        bytesSent.sum(),
                        failed.get(),
                        Math.max(0, holdMillis),
                        elapsedMillis,
                        packetRate,
                        byteRate,
                        latency.samples(),
                        latency.p50Millis(),
                        latency.p95Millis(),
                        latency.p99Millis(),
                        latency.maxMillis());
            }
            return passed ? 0 : 1;
        }
    }

    @Command(name = "compression-rewrite-load", description = "Send compressed Minecraft frames after Set Compression to probe live rewrite throughput.")
    static final class CompressionRewriteLoadCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyQueryCli root;

        @Option(names = "--connections", defaultValue = "100", description = "Number of concurrent login connections.")
        private int connections;

        @Option(names = "--parallelism", defaultValue = "128", description = "Maximum concurrent connection workers.")
        private int parallelism;

        @Option(names = "--protocol", defaultValue = "763", description = "Minecraft protocol version.")
        private int protocolVersion;

        @Option(names = "--virtual-host", description = "Hostname to place in the Minecraft handshake.")
        private String virtualHost;

        @Option(names = "--player-template", defaultValue = "rewrite%05d", description = "Player name template for Login Start. Must contain one integer format placeholder and fit in 16 characters.")
        private String playerTemplate;

        @Option(names = "--packets-per-connection", defaultValue = "100", description = "Number of compressed packet frames each connection sends.")
        private int packetsPerConnection;

        @Option(names = "--payload-bytes", defaultValue = "1024", description = "Zero-filled payload bytes after the packet id before compression.")
        private int payloadBytes;

        @Option(names = "--packet-id", defaultValue = "1", description = "Packet id to use for generated compressed frames.")
        private int packetId;

        @Option(names = "--threshold", defaultValue = "256", description = "Expected Set Compression threshold.")
        private int threshold;

        @Option(names = "--max-negotiation-frame-bytes", defaultValue = "1024", description = "Maximum Set Compression frame size to accept.")
        private int maxNegotiationFrameBytes;

        @Option(names = "--split-frames", description = "Split every generated frame across two writes to exercise partial compressed-frame buffering.")
        private boolean splitFrames;

        @Option(names = "--inter-packet-delay-ms", defaultValue = "0", description = "Optional delay between generated compressed packets on each connection.")
        private long interPacketDelayMillis;

        @Option(names = "--hold-ms", defaultValue = "0", description = "How long to hold connections after sending traffic.")
        private long holdMillis;

        @Option(names = "--min-handshaken", defaultValue = "0", description = "Minimum successful handshakes required for exit code 0.")
        private int minHandshaken;

        @Option(names = "--min-negotiated", defaultValue = "0", description = "Minimum successful Set Compression negotiations required for exit code 0.")
        private int minNegotiated;

        @Option(names = "--min-packets-sent", defaultValue = "0", description = "Minimum sent compressed packet frames required for exit code 0.")
        private long minPacketsSent;

        @Option(names = "--min-bytes-sent", defaultValue = "0", description = "Minimum sent bytes required for exit code 0.")
        private long minBytesSent;

        @Option(names = "--max-failed", defaultValue = "0", description = "Maximum failed connection attempts allowed for exit code 0.")
        private int maxFailed;

        @Option(names = "--min-packet-rate", defaultValue = "0.0", description = "Minimum packets per second required for exit code 0.")
        private double minPacketRate;

        @Option(names = "--min-byte-rate", defaultValue = "0.0", description = "Minimum bytes per second required for exit code 0.")
        private double minByteRate;

        @Option(names = "--json", description = "Print a machine-readable JSON result.")
        private boolean json;

        @Override
        public Integer call() throws Exception {
            validateLoadOptions(connections, parallelism, 0, 1);
            if (packetsPerConnection < 0) {
                throw new IllegalArgumentException("packets-per-connection must be non-negative");
            }
            if (payloadBytes < 0) {
                throw new IllegalArgumentException("payload-bytes must be non-negative");
            }
            if (packetId < 0) {
                throw new IllegalArgumentException("packet-id must be non-negative");
            }
            if (threshold < 0) {
                throw new IllegalArgumentException("threshold must be non-negative");
            }
            if (maxNegotiationFrameBytes <= 0) {
                throw new IllegalArgumentException("max-negotiation-frame-bytes must be positive");
            }
            if (interPacketDelayMillis < 0) {
                throw new IllegalArgumentException("inter-packet-delay-ms must not be negative");
            }
            validatePlayerTemplate(playerTemplate, connections);
            validateAcceptance("min-handshaken", minHandshaken);
            validateAcceptance("min-negotiated", minNegotiated);
            validateAcceptance("min-packets-sent", minPacketsSent);
            validateAcceptance("min-bytes-sent", minBytesSent);
            validateAcceptance("max-failed", maxFailed);
            validateAcceptance("min-packet-rate", minPacketRate);
            validateAcceptance("min-byte-rate", minByteRate);

            var startedAt = Instant.now();
            var sockets = Collections.synchronizedList(new ArrayList<Socket>(connections));
            var attempted = new AtomicInteger();
            var connected = new AtomicInteger();
            var handshaken = new AtomicInteger();
            var negotiated = new AtomicInteger();
            var failed = new AtomicInteger();
            var packetsSent = new java.util.concurrent.atomic.LongAdder();
            var bytesSent = new java.util.concurrent.atomic.LongAdder();
            var address = root.address();
            var handshakeHost = virtualHost == null || virtualHost.isBlank() ? address.getHostString() : virtualHost;
            var handshake = MinecraftQueryProtocol.loginHandshake(protocolVersion, handshakeHost, address.getPort());
            var packet = MinecraftQueryProtocol.compressedPacketFrame(packetId, payloadBytes, threshold);

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                var permits = new java.util.concurrent.Semaphore(parallelism);
                for (var i = 0; i < connections; i++) {
                    var connectionIndex = i;
                    permits.acquire();
                    futures.add(executor.submit(() -> {
                        try {
                            attempted.incrementAndGet();
                            var socket = new Socket();
                            socket.connect(address, root.timeoutMillis());
                            connected.incrementAndGet();
                            sockets.add(socket);
                            socket.setSoTimeout(root.timeoutMillis());
                            var output = socket.getOutputStream();
                            output.write(handshake);
                            output.write(MinecraftQueryProtocol.loginStart(playerName(playerTemplate, connectionIndex)));
                            output.flush();
                            handshaken.incrementAndGet();
                            var negotiatedThreshold = MinecraftQueryProtocol.readCompressionThreshold(
                                    socket.getInputStream(),
                                    maxNegotiationFrameBytes);
                            if (negotiatedThreshold != threshold) {
                                failed.incrementAndGet();
                                return;
                            }
                            negotiated.incrementAndGet();
                            for (var packetIndex = 0; packetIndex < packetsPerConnection; packetIndex++) {
                                if (splitFrames && packet.length > 1) {
                                    var splitIndex = packet.length / 2;
                                    output.write(packet, 0, splitIndex);
                                    output.flush();
                                    output.write(packet, splitIndex, packet.length - splitIndex);
                                } else {
                                    output.write(packet);
                                }
                                packetsSent.increment();
                                bytesSent.add(packet.length);
                                if (interPacketDelayMillis > 0) {
                                    TimeUnit.MILLISECONDS.sleep(interPacketDelayMillis);
                                }
                            }
                            output.flush();
                        } catch (IOException exception) {
                            failed.incrementAndGet();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            failed.incrementAndGet();
                        } finally {
                            permits.release();
                        }
                    }));
                }
                for (var future : futures) {
                    future.get();
                }
                TimeUnit.MILLISECONDS.sleep(Math.max(0, holdMillis));
            } finally {
                for (var socket : sockets) {
                    close(socket);
                }
            }

            var elapsedMillis = Math.max(1, Duration.between(startedAt, Instant.now()).toMillis());
            var packetRate = packetsSent.sum() * 1000.0d / elapsedMillis;
            var byteRate = bytesSent.sum() * 1000.0d / elapsedMillis;
            var passed = failed.get() <= maxFailed
                    && handshaken.get() >= minHandshaken
                    && negotiated.get() >= minNegotiated
                    && packetsSent.sum() >= minPacketsSent
                    && bytesSent.sum() >= minBytesSent
                    && packetRate >= minPacketRate
                    && byteRate >= minByteRate;
            if (json) {
                printJson("compression-rewrite-load", passed,
                        "attempted", attempted.get(),
                        "connected", connected.get(),
                        "handshaken", handshaken.get(),
                        "negotiated", negotiated.get(),
                        "packetsSent", packetsSent.sum(),
                        "bytesSent", bytesSent.sum(),
                        "failed", failed.get(),
                        "heldMillis", Math.max(0, holdMillis),
                        "elapsedMillis", elapsedMillis,
                        "packetRatePerSecond", packetRate,
                        "byteRatePerSecond", byteRate,
                        "splitFrames", splitFrames);
            } else {
                System.out.printf(
                        "attempted=%d connected=%d handshaken=%d negotiated=%d packetsSent=%d bytesSent=%d failed=%d heldMillis=%d elapsedMillis=%d packetRatePerSecond=%.2f byteRatePerSecond=%.2f splitFrames=%s%n",
                        attempted.get(),
                        connected.get(),
                        handshaken.get(),
                        negotiated.get(),
                        packetsSent.sum(),
                        bytesSent.sum(),
                        failed.get(),
                        Math.max(0, holdMillis),
                        elapsedMillis,
                        packetRate,
                        byteRate,
                        splitFrames);
            }
            return passed ? 0 : 1;
        }
    }

    @Command(name = "load-suite", description = "Run a repeatable load-test scenario using the built-in probes.")
    static final class LoadSuiteCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyQueryCli root;

        @Option(names = "--profile", defaultValue = "smoke", description = "Scenario profile: smoke or acceptance.")
        private String profile;

        @Option(names = "--idle-connections", description = "Idle TCP connections. Defaults to 100 for smoke and 10000 for acceptance.")
        private Integer idleConnections;

        @Option(names = "--active-connections", description = "Traffic connections. Defaults to 10 for smoke and 2000 for acceptance.")
        private Integer activeConnections;

        @Option(names = "--parallelism", defaultValue = "256", description = "Maximum concurrent connection workers for each probe.")
        private int parallelism;

        @Option(names = "--virtual-host", description = "Hostname to place in Minecraft handshakes.")
        private String virtualHost;

        @Option(names = "--player-template", defaultValue = "suite%05d", description = "Player name template for active-player traffic.")
        private String playerTemplate;

        @Option(names = "--traffic-packets-per-connection", description = "Traffic packet frames per active connection. Defaults to 10 for smoke and 200 for acceptance.")
        private Integer trafficPacketsPerConnection;

        @Option(names = "--payload-bytes", defaultValue = "64", description = "Traffic payload bytes after packet id.")
        private int payloadBytes;

        @Option(names = "--hold-ms", defaultValue = "0", description = "How long each probe holds connections after sending traffic.")
        private long holdMillis;

        @Option(names = "--settle-ms", defaultValue = "100", description = "Idle-load settle delay before probing sockets.")
        private long settleMillis;

        @Option(names = "--probe-timeout-ms", defaultValue = "50", description = "Idle-load read timeout for alive probes.")
        private int probeTimeoutMillis;

        @Option(names = "--include-compression-rewrite", description = "Also run compression-rewrite-load after traffic-load.")
        private boolean includeCompressionRewrite;

        @Option(names = "--compression-payload-bytes", defaultValue = "1024", description = "Payload bytes for compression-rewrite-load.")
        private int compressionPayloadBytes;

        @Option(names = "--compression-threshold", defaultValue = "256", description = "Expected Set Compression threshold for compression-rewrite-load.")
        private int compressionThreshold;

        @Option(names = "--json", description = "Print a machine-readable JSON suite summary.")
        private boolean json;

        @Override
        public Integer call() {
            var selectedProfile = profile.toLowerCase(Locale.ROOT);
            if (!selectedProfile.equals("smoke") && !selectedProfile.equals("acceptance")) {
                throw new IllegalArgumentException("profile must be smoke or acceptance");
            }
            if (parallelism <= 0) {
                throw new IllegalArgumentException("parallelism must be positive");
            }
            if (payloadBytes < 0) {
                throw new IllegalArgumentException("payload-bytes must be non-negative");
            }
            if (compressionPayloadBytes < 0) {
                throw new IllegalArgumentException("compression-payload-bytes must be non-negative");
            }
            if (compressionThreshold < 0) {
                throw new IllegalArgumentException("compression-threshold must be non-negative");
            }

            var resolvedIdleConnections = idleConnections == null
                    ? (selectedProfile.equals("acceptance") ? 10_000 : 100)
                    : idleConnections;
            var resolvedActiveConnections = activeConnections == null
                    ? (selectedProfile.equals("acceptance") ? 2_000 : 10)
                    : activeConnections;
            var resolvedTrafficPackets = trafficPacketsPerConnection == null
                    ? (selectedProfile.equals("acceptance") ? 200 : 10)
                    : trafficPacketsPerConnection;
            validateLoadOptions(resolvedIdleConnections, parallelism, settleMillis, probeTimeoutMillis);
            validateLoadOptions(resolvedActiveConnections, parallelism, 0, 1);
            if (resolvedTrafficPackets < 0) {
                throw new IllegalArgumentException("traffic-packets-per-connection must be non-negative");
            }
            validatePlayerTemplate(playerTemplate, resolvedActiveConnections);

            var startedAt = Instant.now();
            var results = new ArrayList<SuiteStepResult>();
            results.add(runNested(root,
                    "idle-load",
                    "--connections", Integer.toString(resolvedIdleConnections),
                    "--parallelism", Integer.toString(parallelism),
                    "--settle-ms", Long.toString(settleMillis),
                    "--probe-timeout-ms", Integer.toString(probeTimeoutMillis),
                    "--hold-ms", Long.toString(holdMillis),
                    "--fail-on-closed",
                    "--min-connected", Integer.toString(resolvedIdleConnections),
                    "--min-alive", Integer.toString(resolvedIdleConnections),
                    "--max-failed", "0"));

            var hostArgs = virtualHost == null || virtualHost.isBlank()
                    ? List.<String>of()
                    : List.of("--virtual-host", virtualHost);
            var trafficArgs = new ArrayList<String>();
            Collections.addAll(trafficArgs,
                    "traffic-load",
                    "--connections", Integer.toString(resolvedActiveConnections),
                    "--parallelism", Integer.toString(parallelism),
                    "--login-start",
                    "--player-template", playerTemplate,
                    "--packets-per-connection", Integer.toString(resolvedTrafficPackets),
                    "--payload-bytes", Integer.toString(payloadBytes),
                    "--hold-ms", Long.toString(holdMillis),
                    "--min-handshaken", Integer.toString(resolvedActiveConnections),
                    "--min-packets-sent", Long.toString((long) resolvedActiveConnections * resolvedTrafficPackets),
                    "--max-failed", "0");
            trafficArgs.addAll(hostArgs);
            results.add(runNested(root, trafficArgs.toArray(String[]::new)));

            if (includeCompressionRewrite) {
                var rewriteArgs = new ArrayList<String>();
                Collections.addAll(rewriteArgs,
                        "compression-rewrite-load",
                        "--connections", Integer.toString(resolvedActiveConnections),
                        "--parallelism", Integer.toString(parallelism),
                        "--player-template", playerTemplate,
                        "--packets-per-connection", Integer.toString(resolvedTrafficPackets),
                        "--payload-bytes", Integer.toString(compressionPayloadBytes),
                        "--threshold", Integer.toString(compressionThreshold),
                        "--hold-ms", Long.toString(holdMillis),
                        "--min-handshaken", Integer.toString(resolvedActiveConnections),
                        "--min-negotiated", Integer.toString(resolvedActiveConnections),
                        "--min-packets-sent", Long.toString((long) resolvedActiveConnections * resolvedTrafficPackets),
                        "--max-failed", "0");
                rewriteArgs.addAll(hostArgs);
                results.add(runNested(root, rewriteArgs.toArray(String[]::new)));
            }

            var elapsedMillis = Math.max(1, Duration.between(startedAt, Instant.now()).toMillis());
            var passed = results.stream().allMatch(SuiteStepResult::passed);
            if (json) {
                printJson("load-suite", passed,
                        "profile", selectedProfile,
                        "idleConnections", resolvedIdleConnections,
                        "activeConnections", resolvedActiveConnections,
                        "trafficPacketsPerConnection", resolvedTrafficPackets,
                        "includeCompressionRewrite", includeCompressionRewrite,
                        "steps", suiteStepsJson(results),
                        "elapsedMillis", elapsedMillis);
            } else {
                System.out.printf(
                        "profile=%s idleConnections=%d activeConnections=%d trafficPacketsPerConnection=%d includeCompressionRewrite=%s passed=%s elapsedMillis=%d%n",
                        selectedProfile,
                        resolvedIdleConnections,
                        resolvedActiveConnections,
                        resolvedTrafficPackets,
                        includeCompressionRewrite,
                        passed,
                        elapsedMillis);
                for (var result : results) {
                    System.out.printf("step=%s exitCode=%d output=%s%n",
                            result.name(),
                            result.exitCode(),
                            result.output().replace(System.lineSeparator(), " ").trim());
                }
            }
            return passed ? 0 : 1;
        }
    }

    @Command(name = "idle-load", description = "Open and hold many idle TCP connections to probe admission limits.")
    static final class IdleLoadCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyQueryCli root;

        @Option(names = "--connections", defaultValue = "100", description = "Number of TCP connections to open.")
        private int connections;

        @Option(names = "--hold-ms", defaultValue = "5000", description = "How long to hold successful connections.")
        private long holdMillis;

        @Option(names = "--parallelism", defaultValue = "128", description = "Maximum concurrent connection attempts.")
        private int parallelism;

        @Option(names = "--settle-ms", defaultValue = "100", description = "Delay before probing whether connected sockets are still open.")
        private long settleMillis;

        @Option(names = "--probe-timeout-ms", defaultValue = "50", description = "Read timeout used to distinguish idle-open sockets from closed sockets.")
        private int probeTimeoutMillis;

        @Option(names = "--fail-on-closed", description = "Exit with a non-zero code when any connected socket is closed during the probe.")
        private boolean failOnClosed;

        @Option(names = "--min-connected", defaultValue = "0", description = "Minimum connected sockets required for exit code 0.")
        private int minConnected;

        @Option(names = "--min-alive", defaultValue = "0", description = "Minimum alive sockets required for exit code 0.")
        private int minAlive;

        @Option(names = "--max-closed", defaultValue = "2147483647", description = "Maximum closed sockets allowed for exit code 0.")
        private int maxClosed;

        @Option(names = "--max-failed", defaultValue = "0", description = "Maximum failed connection attempts allowed for exit code 0.")
        private int maxFailed;

        @Option(names = "--min-connect-rate", defaultValue = "0.0", description = "Minimum connects per second required for exit code 0.")
        private double minConnectRate;

        @Option(names = "--json", description = "Print a machine-readable JSON result.")
        private boolean json;

        @Override
        public Integer call() throws Exception {
            validateLoadOptions(connections, parallelism, settleMillis, probeTimeoutMillis);
            validateAcceptance("min-connected", minConnected);
            validateAcceptance("min-alive", minAlive);
            validateAcceptance("max-closed", maxClosed);
            validateAcceptance("max-failed", maxFailed);
            validateAcceptance("min-connect-rate", minConnectRate);
            var startedAt = Instant.now();
            var sockets = Collections.synchronizedList(new ArrayList<Socket>(connections));
            var attempted = new AtomicInteger();
            var connected = new AtomicInteger();
            var failed = new AtomicInteger();
            var alive = new AtomicInteger();
            var closed = new AtomicInteger();

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                var permits = new java.util.concurrent.Semaphore(parallelism);
                for (var i = 0; i < connections; i++) {
                    permits.acquire();
                    futures.add(executor.submit(() -> {
                        try {
                            attempted.incrementAndGet();
                            var socket = new Socket();
                            socket.connect(root.address(), root.timeoutMillis());
                            sockets.add(socket);
                            connected.incrementAndGet();
                        } catch (IOException exception) {
                            failed.incrementAndGet();
                        } finally {
                            permits.release();
                        }
                    }));
                }
                for (var future : futures) {
                    future.get();
                }
                TimeUnit.MILLISECONDS.sleep(settleMillis);
                var probes = new ArrayList<java.util.concurrent.Future<?>>();
                synchronized (sockets) {
                    for (var socket : sockets) {
                        probes.add(executor.submit(() -> {
                            if (isSocketAlive(socket, probeTimeoutMillis)) {
                                alive.incrementAndGet();
                            } else {
                                closed.incrementAndGet();
                            }
                        }));
                    }
                }
                for (var probe : probes) {
                    probe.get();
                }
                TimeUnit.MILLISECONDS.sleep(Math.max(0, holdMillis));
            } finally {
                for (var socket : sockets) {
                    close(socket);
                }
            }

            var elapsedMillis = Math.max(1, Duration.between(startedAt, Instant.now()).toMillis());
            var connectRate = connected.get() * 1000.0d / elapsedMillis;
            var allowedClosed = failOnClosed ? 0 : maxClosed;
            var passed = failed.get() <= maxFailed
                    && connected.get() >= minConnected
                    && alive.get() >= minAlive
                    && closed.get() <= allowedClosed
                    && connectRate >= minConnectRate;
            if (json) {
                printJson("idle-load", passed,
                        "attempted", attempted.get(),
                        "connected", connected.get(),
                        "alive", alive.get(),
                        "closed", closed.get(),
                        "failed", failed.get(),
                        "heldMillis", Math.max(0, holdMillis),
                        "settleMillis", settleMillis,
                        "elapsedMillis", elapsedMillis,
                        "connectRatePerSecond", connectRate);
            } else {
                System.out.printf(
                        "attempted=%d connected=%d alive=%d closed=%d failed=%d heldMillis=%d settleMillis=%d elapsedMillis=%d connectRatePerSecond=%.2f%n",
                        attempted.get(),
                        connected.get(),
                        alive.get(),
                        closed.get(),
                        failed.get(),
                        Math.max(0, holdMillis),
                        settleMillis,
                        elapsedMillis,
                        connectRate);
            }
            return passed ? 0 : 1;
        }
    }

    @Command(name = "slow-sink", description = "Run a slow-reading TCP backend for relay backpressure tests.")
    static final class SlowSinkCommand implements Callable<Integer> {
        @Option(names = "--bind-host", defaultValue = "127.0.0.1", description = "Local address to bind.")
        private String bindHost;

        @Option(names = "--port", defaultValue = "25565", description = "Local port to bind. Use 0 for an ephemeral port.")
        private int port;

        @Option(names = "--duration-ms", defaultValue = "30000", description = "How long the sink server runs.")
        private long durationMillis;

        @Option(names = "--accept-timeout-ms", defaultValue = "100", description = "Accept loop timeout used for shutdown checks.")
        private int acceptTimeoutMillis;

        @Option(names = "--read-chunk-bytes", defaultValue = "1", description = "Bytes to read per socket read. Use 0 to accept and hold without reading.")
        private int readChunkBytes;

        @Option(names = "--read-delay-ms", defaultValue = "100", description = "Delay after each socket read.")
        private long readDelayMillis;

        @Option(names = "--backlog", defaultValue = "1024", description = "Server socket backlog.")
        private int backlog;

        @Override
        public Integer call() throws Exception {
            if (durationMillis < 0) {
                throw new IllegalArgumentException("duration-ms must not be negative");
            }
            if (acceptTimeoutMillis <= 0) {
                throw new IllegalArgumentException("accept-timeout-ms must be positive");
            }
            if (readChunkBytes < 0) {
                throw new IllegalArgumentException("read-chunk-bytes must not be negative");
            }
            if (readDelayMillis < 0) {
                throw new IllegalArgumentException("read-delay-ms must not be negative");
            }
            if (backlog <= 0) {
                throw new IllegalArgumentException("backlog must be positive");
            }

            var accepted = new AtomicInteger();
            var closed = new AtomicInteger();
            var failed = new AtomicInteger();
            var bytesRead = new java.util.concurrent.atomic.LongAdder();
            var startedAt = Instant.now();
            var deadline = startedAt.plusMillis(durationMillis);

            try (var server = new ServerSocket()) {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(bindHost, port), backlog);
                server.setSoTimeout(acceptTimeoutMillis);
                System.out.printf("listening=%s:%d readChunkBytes=%d readDelayMillis=%d durationMillis=%d%n",
                        server.getInetAddress().getHostAddress(),
                        server.getLocalPort(),
                        readChunkBytes,
                        readDelayMillis,
                        durationMillis);
                System.out.flush();

                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    while (!Instant.now().isAfter(deadline)) {
                        try {
                            var socket = server.accept();
                            accepted.incrementAndGet();
                            executor.submit(() -> handleSlowSinkSocket(
                                    socket,
                                    readChunkBytes,
                                    readDelayMillis,
                                    acceptTimeoutMillis,
                                    deadline,
                                    bytesRead,
                                    closed,
                                    failed));
                        } catch (SocketTimeoutException exception) {
                            // Timeout is the normal shutdown check cadence.
                        }
                    }
                }
            }

            var elapsedMillis = Math.max(1, Duration.between(startedAt, Instant.now()).toMillis());
            System.out.printf(
                    "accepted=%d closed=%d failed=%d bytesRead=%d elapsedMillis=%d readBytesPerSecond=%.2f%n",
                    accepted.get(),
                    closed.get(),
                    failed.get(),
                    bytesRead.sum(),
                    elapsedMillis,
                    bytesRead.sum() * 1000.0d / elapsedMillis);
            return failed.get() == 0 ? 0 : 1;
        }
    }

    private static void validateLoadOptions(int connections, int parallelism, long settleMillis, int probeTimeoutMillis) {
        if (connections <= 0) {
            throw new IllegalArgumentException("connections must be positive");
        }
        if (parallelism <= 0) {
            throw new IllegalArgumentException("parallelism must be positive");
        }
        if (settleMillis < 0) {
            throw new IllegalArgumentException("settle-ms must not be negative");
        }
        if (probeTimeoutMillis <= 0) {
            throw new IllegalArgumentException("probe-timeout-ms must be positive");
        }
    }

    private static void validateAcceptance(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void validateAcceptance(String name, double value) {
        if (value < 0.0d || Double.isNaN(value)) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void validateHostTemplate(String template) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("virtual-host-template must not be blank");
        }
        var first = routeHost(template, 0);
        var second = routeHost(template, 1);
        if (first.equals(second)) {
            throw new IllegalArgumentException("virtual-host-template must include a varying integer placeholder such as %d");
        }
    }

    private static void validatePlayerTemplate(String template, int connections) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("player-template must not be blank");
        }
        var first = playerName(template, 0);
        var last = playerName(template, Math.max(0, connections - 1));
        if (first.equals(last) && connections > 1) {
            throw new IllegalArgumentException("player-template must include a varying integer placeholder such as %d");
        }
        if (first.length() > 16 || last.length() > 16) {
            throw new IllegalArgumentException("player-template must generate names no longer than 16 characters");
        }
    }

    private static String routeHost(String template, int routeIndex) {
        try {
            return String.format(Locale.ROOT, template, routeIndex);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("virtual-host-template must be a valid integer String.format template", exception);
        }
    }

    private static String playerName(String template, int playerIndex) {
        try {
            return String.format(Locale.ROOT, template, playerIndex);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("player-template must be a valid integer String.format template", exception);
        }
    }

    private static boolean isSocketAlive(Socket socket, int probeTimeoutMillis) {
        try {
            socket.setSoTimeout(probeTimeoutMillis);
            return socket.getInputStream().read() != -1;
        } catch (SocketTimeoutException exception) {
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void handleSlowSinkSocket(
            Socket socket,
            int readChunkBytes,
            long readDelayMillis,
            int readTimeoutMillis,
            Instant deadline,
            java.util.concurrent.atomic.LongAdder bytesRead,
            AtomicInteger closed,
            AtomicInteger failed) {
        try (socket) {
            socket.setSoTimeout(readTimeoutMillis);
            if (readChunkBytes == 0) {
                while (Instant.now().isBefore(deadline)) {
                    TimeUnit.MILLISECONDS.sleep(Math.max(1, readDelayMillis));
                }
                closed.incrementAndGet();
                return;
            }
            var input = socket.getInputStream();
            var buffer = new byte[readChunkBytes];
            while (Instant.now().isBefore(deadline)) {
                int read;
                try {
                    read = input.read(buffer);
                } catch (SocketTimeoutException exception) {
                    continue;
                }
                if (read < 0) {
                    closed.incrementAndGet();
                    return;
                }
                bytesRead.add(read);
                if (readDelayMillis > 0) {
                    TimeUnit.MILLISECONDS.sleep(readDelayMillis);
                }
            }
            closed.incrementAndGet();
        } catch (IOException exception) {
            failed.incrementAndGet();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failed.incrementAndGet();
        }
    }

    private static SuiteStepResult runNested(StrataProxyQueryCli root, String... args) {
        var nested = new ArrayList<String>();
        Collections.addAll(nested,
                "--host", root.host,
                "--port", Integer.toString(root.port),
                "--timeout-ms", Integer.toString(root.timeoutMillis));
        Collections.addAll(nested, args);
        var originalOut = System.out;
        var output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            var exitCode = new CommandLine(new StrataProxyQueryCli()).execute(nested.toArray(String[]::new));
            return new SuiteStepResult(args[0], exitCode, output.toString(StandardCharsets.UTF_8).trim());
        } finally {
            System.setOut(originalOut);
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException exception) {
            // Best effort cleanup after the probe result has already been recorded.
        }
    }

    private static void printJson(String command, boolean passed, Object... fields) {
        var body = new StringBuilder(256);
        body.append('{');
        appendJsonField(body, "command", command);
        body.append(',');
        appendJsonField(body, "passed", passed);
        for (var i = 0; i < fields.length; i += 2) {
            body.append(',');
            appendJsonField(body, (String) fields[i], fields[i + 1]);
        }
        body.append('}');
        System.out.println(body);
    }

    private static void appendJsonField(StringBuilder body, String name, Object value) {
        body.append('"').append(jsonEscape(name)).append("\":");
        if (value instanceof Number number) {
            body.append(formatNumber(number));
        } else if (value instanceof Boolean bool) {
            body.append(bool);
        } else if (value instanceof RawJson rawJson) {
            body.append(rawJson.value());
        } else {
            body.append('"').append(jsonEscape(String.valueOf(value))).append('"');
        }
    }

    private static RawJson suiteStepsJson(List<SuiteStepResult> results) {
        var body = new StringBuilder();
        body.append('[');
        for (var i = 0; i < results.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            var result = results.get(i);
            body.append('{');
            appendJsonField(body, "name", result.name());
            body.append(',');
            appendJsonField(body, "exitCode", result.exitCode());
            body.append(',');
            appendJsonField(body, "passed", result.passed());
            body.append(',');
            appendJsonField(body, "output", result.output());
            body.append('}');
        }
        body.append(']');
        return new RawJson(body.toString());
    }

    private static String formatNumber(Number value) {
        if (value instanceof Float || value instanceof Double) {
            return String.format(Locale.ROOT, "%.6f", value.doubleValue());
        }
        return value.toString();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private record LatencySummary(
            int samples,
            double p50Millis,
            double p95Millis,
            double p99Millis,
            double maxMillis) {
        private static LatencySummary from(List<Long> nanos) {
            if (nanos.isEmpty()) {
                return new LatencySummary(0, 0.0d, 0.0d, 0.0d, 0.0d);
            }
            var sorted = new ArrayList<Long>(nanos);
            Collections.sort(sorted);
            return new LatencySummary(
                    sorted.size(),
                    millis(percentile(sorted, 0.50d)),
                    millis(percentile(sorted, 0.95d)),
                    millis(percentile(sorted, 0.99d)),
                    millis(sorted.get(sorted.size() - 1)));
        }

        private static long percentile(List<Long> sorted, double percentile) {
            var index = (int) Math.ceil(percentile * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
        }

        private static double millis(long nanos) {
            return nanos / 1_000_000.0d;
        }
    }

    private record SuiteStepResult(String name, int exitCode, String output) {
        private boolean passed() {
            return exitCode == 0;
        }
    }

    private record RawJson(String value) {
    }
}

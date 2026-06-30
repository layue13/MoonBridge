package dev.strataproxy.query;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StrataProxyQueryCliTest {
    @Test
    void idleLoadReportsAliveConnections() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "idle-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--settle-ms", "25",
                    "--probe-timeout-ms", "25",
                    "--hold-ms", "0");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("connected=1"));
            assertTrue(result.output().contains("alive=1"));
            assertTrue(result.output().contains("closed=0"));
            assertTrue(result.output().contains("failed=0"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void idleLoadReportsClosedConnectionsAndCanFailOnClosed() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.close();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "idle-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--settle-ms", "25",
                    "--probe-timeout-ms", "25",
                    "--hold-ms", "0",
                    "--fail-on-closed");

            assertEquals(1, result.exitCode());
            assertTrue(result.output().contains("connected=1"));
            assertTrue(result.output().contains("alive=0"));
            assertTrue(result.output().contains("closed=1"));
            assertTrue(result.output().contains("failed=0"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void idleLoadFailsWhenAcceptanceThresholdsAreNotMet() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "idle-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--settle-ms", "25",
                    "--probe-timeout-ms", "25",
                    "--hold-ms", "0",
                    "--min-connected", "2");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(1, result.exitCode());
            assertTrue(result.output().contains("connected=1"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void idleLoadCanPrintJsonResult() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "idle-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--settle-ms", "25",
                    "--probe-timeout-ms", "25",
                    "--hold-ms", "0",
                    "--json");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(0, result.exitCode());
            assertTrue(result.output().startsWith("{\"command\":\"idle-load\",\"passed\":true"));
            assertTrue(result.output().contains("\"connected\":1"));
            assertTrue(result.output().contains("\"alive\":1"));
            assertTrue(result.output().contains("\"connectRatePerSecond\":"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void handshakeLoadSendsLoginHandshakesAndReportsAliveConnections() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new DataInputStream(socket.getInputStream());
                    var frame = input.readNBytes(readVarInt(input));
                    var frameInput = new DataInputStream(new java.io.ByteArrayInputStream(frame));
                    assertEquals(0, readVarInt(frameInput));
                    assertEquals(763, readVarInt(frameInput));
                    readString(frameInput);
                    frameInput.readUnsignedShort();
                    assertEquals(2, readVarInt(frameInput));
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "handshake-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--settle-ms", "25",
                    "--probe-timeout-ms", "25",
                    "--hold-ms", "0",
                    "--virtual-host", "play.example.net");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("connected=1"));
            assertTrue(result.output().contains("handshaken=1"));
            assertTrue(result.output().contains("alive=1"));
            assertTrue(result.output().contains("closed=0"));
            assertTrue(result.output().contains("failed=0"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void handshakeLoadFailsWhenAcceptanceThresholdsAreNotMet() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new DataInputStream(socket.getInputStream());
                    input.readNBytes(readVarInt(input));
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "handshake-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--settle-ms", "25",
                    "--probe-timeout-ms", "25",
                    "--hold-ms", "0",
                    "--virtual-host", "play.example.net",
                    "--min-handshaken", "2");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(1, result.exitCode());
            assertTrue(result.output().contains("handshaken=1"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void handshakeLoadCanPrintJsonResult() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new DataInputStream(socket.getInputStream());
                    input.readNBytes(readVarInt(input));
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "handshake-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--settle-ms", "25",
                    "--probe-timeout-ms", "25",
                    "--hold-ms", "0",
                    "--virtual-host", "play.example.net",
                    "--json");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(0, result.exitCode());
            assertTrue(result.output().startsWith("{\"command\":\"handshake-load\",\"passed\":true"));
            assertTrue(result.output().contains("\"handshaken\":1"));
            assertTrue(result.output().contains("\"alive\":1"));
            assertTrue(result.output().contains("\"handshakeRatePerSecond\":"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void routeStormCyclesVirtualHostsAndReportsDistinctRoutes() throws Exception {
        var accepted = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var hosts = ConcurrentHashMap.<String>newKeySet();
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                var sockets = new java.util.ArrayList<Socket>();
                try {
                    for (var i = 0; i < 3; i++) {
                        var socket = server.accept();
                        sockets.add(socket);
                        socket.setSoTimeout(5_000);
                        var input = new DataInputStream(socket.getInputStream());
                        var frame = input.readNBytes(readVarInt(input));
                        var frameInput = new DataInputStream(new java.io.ByteArrayInputStream(frame));
                        assertEquals(0, readVarInt(frameInput));
                        assertEquals(763, readVarInt(frameInput));
                        hosts.add(readString(frameInput));
                        frameInput.readUnsignedShort();
                        assertEquals(2, readVarInt(frameInput));
                        accepted.countDown();
                    }
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                } finally {
                    for (var socket : sockets) {
                        try {
                            socket.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "route-storm",
                    "--connections", "3",
                    "--routes", "3",
                    "--parallelism", "3",
                    "--settle-ms", "25",
                    "--probe-timeout-ms", "25",
                    "--hold-ms", "0",
                    "--virtual-host-template", "route-%d.example.net",
                    "--min-handshaken", "3",
                    "--min-routes-handshaken", "3");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(0, result.exitCode());
            assertTrue(hosts.contains("route-0.example.net"), hosts.toString());
            assertTrue(hosts.contains("route-1.example.net"), hosts.toString());
            assertTrue(hosts.contains("route-2.example.net"), hosts.toString());
            assertTrue(result.output().contains("handshaken=3"));
            assertTrue(result.output().contains("routesConfigured=3"));
            assertTrue(result.output().contains("routesAttempted=3"));
            assertTrue(result.output().contains("routesHandshaken=3"));
            assertTrue(result.output().contains("routeRatePerSecond="));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void routeStormCanPrintJsonResult() throws Exception {
        var accepted = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                var sockets = new java.util.ArrayList<Socket>();
                try {
                    for (var i = 0; i < 2; i++) {
                        var socket = server.accept();
                        sockets.add(socket);
                        socket.setSoTimeout(5_000);
                        var input = new DataInputStream(socket.getInputStream());
                        input.readNBytes(readVarInt(input));
                        accepted.countDown();
                    }
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                } finally {
                    for (var socket : sockets) {
                        try {
                            socket.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "route-storm",
                    "--connections", "2",
                    "--routes", "2",
                    "--parallelism", "2",
                    "--settle-ms", "25",
                    "--probe-timeout-ms", "25",
                    "--hold-ms", "0",
                    "--json");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(0, result.exitCode());
            assertTrue(result.output().startsWith("{\"command\":\"route-storm\",\"passed\":true"));
            assertTrue(result.output().contains("\"routesAttempted\":2"));
            assertTrue(result.output().contains("\"routesHandshaken\":2"));
            assertTrue(result.output().contains("\"routeRatePerSecond\":"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void routeStormFailsWhenRouteThresholdsAreNotMet() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new DataInputStream(socket.getInputStream());
                    input.readNBytes(readVarInt(input));
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "route-storm",
                    "--connections", "1",
                    "--routes", "1",
                    "--parallelism", "1",
                    "--settle-ms", "25",
                    "--probe-timeout-ms", "25",
                    "--hold-ms", "0",
                    "--min-routes-handshaken", "2");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(1, result.exitCode());
            assertTrue(result.output().contains("routesHandshaken=1"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void trafficLoadSendsHandshakeAndPacketFrames() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new DataInputStream(socket.getInputStream());
                    var handshake = input.readNBytes(readVarInt(input));
                    var handshakeInput = new DataInputStream(new java.io.ByteArrayInputStream(handshake));
                    assertEquals(0, readVarInt(handshakeInput));
                    readVarInt(handshakeInput);
                    readString(handshakeInput);
                    handshakeInput.readUnsignedShort();
                    assertEquals(2, readVarInt(handshakeInput));

                    for (var i = 0; i < 2; i++) {
                        var packet = input.readNBytes(readVarInt(input));
                        var packetInput = new DataInputStream(new java.io.ByteArrayInputStream(packet));
                        assertEquals(7, readVarInt(packetInput));
                        assertEquals(5, packetInput.readNBytes(8).length);
                    }
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "traffic-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--packets-per-connection", "2",
                    "--payload-bytes", "5",
                    "--packet-id", "7",
                    "--hold-ms", "0",
                    "--virtual-host", "play.example.net");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("connected=1"));
            assertTrue(result.output().contains("handshaken=1"));
            assertTrue(result.output().contains("packetsSent=2"));
            assertTrue(result.output().contains("bytesSent=14"));
            assertTrue(result.output().contains("failed=0"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void trafficLoadCanSendLoginStartPlayerNamesBeforeTraffic() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new DataInputStream(socket.getInputStream());
                    input.readNBytes(readVarInt(input));

                    var loginStart = input.readNBytes(readVarInt(input));
                    var loginStartInput = new DataInputStream(new java.io.ByteArrayInputStream(loginStart));
                    assertEquals(0, readVarInt(loginStartInput));
                    assertEquals("player-0", readString(loginStartInput));

                    var packet = input.readNBytes(readVarInt(input));
                    var packetInput = new DataInputStream(new java.io.ByteArrayInputStream(packet));
                    assertEquals(7, readVarInt(packetInput));
                    assertEquals(5, packetInput.readNBytes(8).length);
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "traffic-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--login-start",
                    "--player-template", "player-%d",
                    "--packets-per-connection", "1",
                    "--payload-bytes", "5",
                    "--packet-id", "7",
                    "--hold-ms", "0",
                    "--virtual-host", "play.example.net");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("loginStartsSent=1"));
            assertTrue(result.output().contains("loginStartBytesSent=11"));
            assertTrue(result.output().contains("packetsSent=1"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void trafficLoadCanPrintJsonResult() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new DataInputStream(socket.getInputStream());
                    input.readNBytes(readVarInt(input));
                    input.readNBytes(readVarInt(input));
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "traffic-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--packets-per-connection", "1",
                    "--payload-bytes", "5",
                    "--packet-id", "7",
                    "--hold-ms", "0",
                    "--virtual-host", "play.example.net",
                    "--json");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(0, result.exitCode());
            assertTrue(result.output().startsWith("{\"command\":\"traffic-load\",\"passed\":true"));
            assertTrue(result.output().contains("\"loginStartsSent\":0"));
            assertTrue(result.output().contains("\"loginStartBytesSent\":0"));
            assertTrue(result.output().contains("\"packetsSent\":1"));
            assertTrue(result.output().contains("\"bytesSent\":7"));
            assertTrue(result.output().contains("\"packetRatePerSecond\":"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void compressionRewriteLoadNegotiatesCompressionAndSendsSplitFrames() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new DataInputStream(socket.getInputStream());
                    input.readNBytes(readVarInt(input));

                    var loginStart = input.readNBytes(readVarInt(input));
                    var loginStartInput = new DataInputStream(new java.io.ByteArrayInputStream(loginStart));
                    assertEquals(0, readVarInt(loginStartInput));
                    assertEquals("rewrite00000", readString(loginStartInput));

                    socket.getOutputStream().write(setCompression(64));
                    socket.getOutputStream().flush();

                    for (var i = 0; i < 2; i++) {
                        var packet = input.readNBytes(readVarInt(input));
                        var packetInput = new DataInputStream(new java.io.ByteArrayInputStream(packet));
                        assertTrue(readVarInt(packetInput) > 64);
                        assertTrue(packetInput.readAllBytes().length > 0);
                    }
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "compression-rewrite-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--packets-per-connection", "2",
                    "--payload-bytes", "512",
                    "--packet-id", "7",
                    "--threshold", "64",
                    "--split-frames",
                    "--hold-ms", "0",
                    "--virtual-host", "play.example.net",
                    "--min-handshaken", "1",
                    "--min-negotiated", "1",
                    "--min-packets-sent", "2",
                    "--max-failed", "0");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("connected=1"));
            assertTrue(result.output().contains("handshaken=1"));
            assertTrue(result.output().contains("negotiated=1"));
            assertTrue(result.output().contains("packetsSent=2"));
            assertTrue(result.output().contains("failed=0"));
            assertTrue(result.output().contains("splitFrames=true"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void compressionRewriteLoadCanPrintJsonResult() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new DataInputStream(socket.getInputStream());
                    input.readNBytes(readVarInt(input));
                    input.readNBytes(readVarInt(input));
                    socket.getOutputStream().write(setCompression(64));
                    socket.getOutputStream().flush();
                    input.readNBytes(readVarInt(input));
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "compression-rewrite-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--packets-per-connection", "1",
                    "--payload-bytes", "512",
                    "--threshold", "64",
                    "--hold-ms", "0",
                    "--json");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(0, result.exitCode());
            assertTrue(result.output().startsWith("{\"command\":\"compression-rewrite-load\",\"passed\":true"));
            assertTrue(result.output().contains("\"negotiated\":1"));
            assertTrue(result.output().contains("\"packetsSent\":1"));
            assertTrue(result.output().contains("\"splitFrames\":false"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void loadSuiteRunsIdleAndTrafficProbes() throws Exception {
        var trafficObserved = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var idleSocket = server.accept()) {
                    idleSocket.setSoTimeout(5_000);
                    while (idleSocket.getInputStream().read() >= 0) {
                        // Wait for idle-load to finish probing and close the socket.
                    }
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
                try (var trafficSocket = server.accept()) {
                    trafficSocket.setSoTimeout(5_000);
                    var input = new DataInputStream(trafficSocket.getInputStream());
                    input.readNBytes(readVarInt(input));
                    input.readNBytes(readVarInt(input));
                    input.readNBytes(readVarInt(input));
                    trafficObserved.countDown();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "load-suite",
                    "--profile", "smoke",
                    "--idle-connections", "1",
                    "--active-connections", "1",
                    "--traffic-packets-per-connection", "1",
                    "--parallelism", "1",
                    "--payload-bytes", "5",
                    "--hold-ms", "0",
                    "--settle-ms", "25",
                    "--probe-timeout-ms", "25",
                    "--virtual-host", "play.example.net");

            assertTrue(trafficObserved.await(5, TimeUnit.SECONDS));
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("profile=smoke"));
            assertTrue(result.output().contains("idleConnections=1"));
            assertTrue(result.output().contains("activeConnections=1"));
            assertTrue(result.output().contains("step=idle-load exitCode=0"));
            assertTrue(result.output().contains("step=traffic-load exitCode=0"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void loadSuiteCanPrintJsonResult() throws Exception {
        var trafficObserved = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var idleSocket = server.accept()) {
                    idleSocket.setSoTimeout(5_000);
                    while (idleSocket.getInputStream().read() >= 0) {
                        // Wait for idle-load to finish probing and close the socket.
                    }
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
                try (var trafficSocket = server.accept()) {
                    trafficSocket.setSoTimeout(5_000);
                    var input = new DataInputStream(trafficSocket.getInputStream());
                    input.readNBytes(readVarInt(input));
                    input.readNBytes(readVarInt(input));
                    input.readNBytes(readVarInt(input));
                    trafficObserved.countDown();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "load-suite",
                    "--profile", "smoke",
                    "--idle-connections", "1",
                    "--active-connections", "1",
                    "--traffic-packets-per-connection", "1",
                    "--parallelism", "1",
                    "--payload-bytes", "5",
                    "--hold-ms", "0",
                    "--settle-ms", "25",
                    "--probe-timeout-ms", "25",
                    "--json");

            assertTrue(trafficObserved.await(5, TimeUnit.SECONDS));
            assertEquals(0, result.exitCode());
            assertTrue(result.output().startsWith("{\"command\":\"load-suite\",\"passed\":true"));
            assertTrue(result.output().contains("\"profile\":\"smoke\""));
            assertTrue(result.output().contains("\"steps\":["));
            assertTrue(result.output().contains("\"name\":\"idle-load\""));
            assertTrue(result.output().contains("\"name\":\"traffic-load\""));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void trafficLoadFailsWhenAcceptanceThresholdsAreNotMet() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new DataInputStream(socket.getInputStream());
                    input.readNBytes(readVarInt(input));
                    input.readNBytes(readVarInt(input));
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "traffic-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--packets-per-connection", "1",
                    "--payload-bytes", "5",
                    "--packet-id", "7",
                    "--hold-ms", "0",
                    "--virtual-host", "play.example.net",
                    "--min-packets-sent", "2");

            release.countDown();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertEquals(1, result.exitCode());
            assertTrue(result.output().contains("packetsSent=1"));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void trafficLoadMeasuresEchoLatency() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new DataInputStream(socket.getInputStream());
                    input.readNBytes(readVarInt(input));
                    for (var i = 0; i < 2; i++) {
                        var packet = readFrameBytes(input);
                        socket.getOutputStream().write(packet);
                        socket.getOutputStream().flush();
                    }
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "--timeout-ms", "1000",
                    "traffic-load",
                    "--connections", "1",
                    "--parallelism", "1",
                    "--packets-per-connection", "2",
                    "--payload-bytes", "5",
                    "--packet-id", "7",
                    "--hold-ms", "0",
                    "--virtual-host", "play.example.net",
                    "--measure-echo-latency",
                    "--max-p99-latency-ms", "1000");

            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("packetsSent=2"));
            assertTrue(result.output().contains("latencySamples=2"));
            assertTrue(result.output().contains("p99LatencyMillis="));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void slowSinkAcceptsConnectionsAndReportsReadCounters() throws Exception {
        var port = freePort();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> execute(
                    "slow-sink",
                    "--bind-host", "127.0.0.1",
                    "--port", Integer.toString(port),
                    "--duration-ms", "250",
                    "--accept-timeout-ms", "25",
                    "--read-chunk-bytes", "2",
                    "--read-delay-ms", "0"));

            try (var socket = connectWithRetry(port)) {
                socket.getOutputStream().write(new byte[] {1, 2, 3, 4, 5, 6});
                socket.getOutputStream().flush();
            }

            var result = future.get(5, TimeUnit.SECONDS);
            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains("listening=127.0.0.1:" + port));
            assertTrue(result.output().contains("readChunkBytes=2"));
            assertTrue(result.output().contains("accepted=1"));
            assertTrue(result.output().contains("failed=0"));
            assertTrue(result.output().contains("bytesRead=6"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void statusCommandPrintsMinecraftStatusJson() throws Exception {
        var status = "{\"players\":{\"online\":0,\"max\":100}}";
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var future = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new DataInputStream(socket.getInputStream());
                    input.readNBytes(readVarInt(input));
                    input.readNBytes(readVarInt(input));
                    socket.getOutputStream().write(statusResponse(status));
                    socket.getOutputStream().flush();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            var result = execute(
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(server.getLocalPort()),
                    "status",
                    "--virtual-host", "play.example.net");

            assertEquals(0, result.exitCode());
            assertTrue(result.output().contains(status));
            future.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Result execute(String... args) {
        var originalOut = System.out;
        var output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            var exitCode = new CommandLine(new StrataProxyQueryCli()).execute(args);
            return new Result(exitCode, output.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
        }
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Socket connectWithRetry(int port) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        SocketException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return new Socket("127.0.0.1", port);
            } catch (SocketException exception) {
                last = exception;
                TimeUnit.MILLISECONDS.sleep(10);
            }
        }
        throw last == null ? new SocketException("could not connect to slow sink") : last;
    }

    private static byte[] statusResponse(String json) {
        var payload = new ByteArrayOutputStream();
        MinecraftQueryProtocol.writeVarInt(payload, 0);
        var jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        MinecraftQueryProtocol.writeVarInt(payload, jsonBytes.length);
        payload.writeBytes(jsonBytes);

        var frame = new ByteArrayOutputStream();
        MinecraftQueryProtocol.writeVarInt(frame, payload.size());
        frame.writeBytes(payload.toByteArray());
        return frame.toByteArray();
    }

    private static byte[] setCompression(int threshold) {
        var payload = new ByteArrayOutputStream();
        MinecraftQueryProtocol.writeVarInt(payload, 3);
        MinecraftQueryProtocol.writeVarInt(payload, threshold);

        var frame = new ByteArrayOutputStream();
        MinecraftQueryProtocol.writeVarInt(frame, payload.size());
        frame.writeBytes(payload.toByteArray());
        return frame.toByteArray();
    }

    private static int readVarInt(DataInputStream input) throws Exception {
        var value = 0;
        var position = 0;
        while (position < 5) {
            var current = input.readUnsignedByte();
            value |= (current & 0x7F) << (position * 7);
            position++;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("malformed VarInt");
    }

    private static byte[] readFrameBytes(DataInputStream input) throws Exception {
        var frame = new ByteArrayOutputStream();
        var value = 0;
        var position = 0;
        while (position < 5) {
            var current = input.readUnsignedByte();
            frame.write(current);
            value |= (current & 0x7F) << (position * 7);
            position++;
            if ((current & 0x80) == 0) {
                frame.writeBytes(input.readNBytes(value));
                return frame.toByteArray();
            }
        }
        throw new IllegalArgumentException("malformed VarInt");
    }

    private static String readString(DataInputStream input) throws Exception {
        var bytes = input.readNBytes(readVarInt(input));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record Result(int exitCode, String output) {
    }
}

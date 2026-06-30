package dev.strataproxy.app;

import dev.strataproxy.bootstrap.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StrataProxyLauncherValidationTest {
    @Test
    void validatesExistingConfigWithoutStartingProxy(@TempDir Path tempDir) throws Exception {
        var config = tempDir.resolve("strataproxy.yml");
        Files.writeString(config, """
                network:
                  bind: "127.0.0.1:0"
                admin:
                  enabled: false
                servers:
                  - name: "lobby-1"
                    address: "127.0.0.1:25565"
                    tags: ["lobby"]
                    capabilities: ["modern-forwarding"]
                    protocolRange: "any"
                    softCapacity: 100
                    hardCapacity: 120
                """);

        var result = capture(() -> StrataProxyLauncher.run(new String[] {"--validate-config", config.toString()}));

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("StrataProxy config OK"));
        assertTrue(result.output().contains("configured servers: 1"));
    }

    @Test
    void validatesDynamicOnlyConfigWithoutStaticServers(@TempDir Path tempDir) throws Exception {
        var config = tempDir.resolve("dynamic-only.yml");
        Files.writeString(config, """
                network:
                  bind: "127.0.0.1:0"
                registry:
                  staticServers: false
                  persistenceEnabled: true
                admin:
                  enabled: true
                servers:
                  - name: "ignored-static"
                    address: "127.0.0.1:25565"
                """);

        var result = capture(() -> StrataProxyLauncher.run(new String[] {"--validate-config", config.toString()}));

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("WARN no static servers configured"));
        assertTrue(result.output().contains("configured servers: 0"));
    }

    @Test
    void validatesTlsRelativePathsFromConfigDirectory(@TempDir Path tempDir) throws Exception {
        var configDirectory = tempDir.resolve("conf");
        var tlsDirectory = configDirectory.resolve("tls");
        Files.createDirectories(tlsDirectory);
        Files.write(tlsDirectory.resolve("admin-server.p12"), new byte[] {1});
        var config = configDirectory.resolve("strataproxy.yml");
        Files.writeString(config, """
                network:
                  bind: "127.0.0.1:0"
                registry:
                  persistenceEnabled: false
                admin:
                  enabled: true
                  bind: "127.0.0.1:0"
                  tls:
                    enabled: true
                    keyStorePath: "tls/admin-server.p12"
                    keyStorePassword: "changeit"
                    keyStoreType: "PKCS12"
                servers:
                  - name: "lobby-1"
                    address: "127.0.0.1:25565"
                """);

        var result = capture(() -> StrataProxyLauncher.run(new String[] {"--validate-config", config.toString()}));

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("StrataProxy config OK"));
    }

    @Test
    void reportsInvalidConfigDuringValidation(@TempDir Path tempDir) throws Exception {
        var config = tempDir.resolve("bad.yml");
        Files.writeString(config, """
                network:
                  bind: "127.0.0.1:0"
                  maxFrameBytes: "64b"
                admin:
                  enabled: false
                servers:
                  - name: "bad"
                    address: "127.0.0.1:25565"
                    softCapacity: 200
                    hardCapacity: 100
                """);

        var result = capture(() -> StrataProxyLauncher.run(new String[] {"--validate-config", config.toString()}));

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("ERROR network.maxFrameBytes"));
        assertTrue(result.output().contains("ERROR server.bad softCapacity"));
    }

    @Test
    void reportsMissingConfigDuringValidation(@TempDir Path tempDir) throws Exception {
        var missing = tempDir.resolve("missing.yml");

        var result = capture(() -> StrataProxyLauncher.run(new String[] {"--validate-config", missing.toString()}));

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("config missing"));
    }

    @Test
    void printsHelpWithoutLoadingConfig() throws Exception {
        var result = capture(() -> StrataProxyLauncher.run(new String[] {"--help"}));

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Usage: strataproxy"));
        assertTrue(result.output().contains("--validate-config"));
    }

    @Test
    void printsVersionWithoutLoadingConfig() throws Exception {
        var result = capture(() -> StrataProxyLauncher.run(new String[] {"--version"}));

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("StrataProxy 0.1.0-SNAPSHOT"));
    }

    @Test
    void rejectsUnknownOptions() throws Exception {
        var result = capture(() -> StrataProxyLauncher.run(new String[] {"--bogus"}));

        assertEquals(2, result.exitCode());
        assertTrue(result.error().contains("Unknown option: --bogus"));
        assertTrue(result.error().contains("Usage: strataproxy"));
    }

    @Test
    void rejectsMultipleConfigPaths(@TempDir Path tempDir) throws Exception {
        var first = tempDir.resolve("one.yml");
        var second = tempDir.resolve("two.yml");

        var result = capture(() -> StrataProxyLauncher.run(new String[] {first.toString(), second.toString()}));

        assertEquals(2, result.exitCode());
        assertTrue(result.error().contains("Only one config path"));
    }

    @Test
    void reportsMissingExplicitConfigDuringStartup(@TempDir Path tempDir) throws Exception {
        var missing = tempDir.resolve("missing.yml");

        var result = capture(() -> StrataProxyLauncher.run(new String[] {missing.toString()}));

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("config missing"));
    }

    @Test
    void reportsConfigParseErrorsDuringValidation(@TempDir Path tempDir) throws Exception {
        var config = tempDir.resolve("bad-unit.yml");
        Files.writeString(config, """
                network:
                  bind: "127.0.0.1:0"
                  maxFrameBytes: "64parsecs"
                admin:
                  enabled: false
                servers:
                  - name: "lobby-1"
                    address: "127.0.0.1:25565"
                """);

        var result = capture(() -> StrataProxyLauncher.run(new String[] {"--validate-config", config.toString()}));

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("ERROR failed to parse config"));
        assertTrue(result.output().contains("unsupported byte unit"));
    }

    @Test
    void reportsStartupBindFailureWithoutThrowing(@TempDir Path tempDir) throws Exception {
        try (var occupied = new ServerSocket(0)) {
            var config = tempDir.resolve("strataproxy.yml");
            Files.writeString(config, """
                    network:
                      bind: "127.0.0.1:%d"
                      nativeTransport: false
                    registry:
                      persistenceEnabled: false
                      healthCheckEnabled: false
                    admin:
                      enabled: false
                    servers:
                      - name: "lobby-1"
                        address: "127.0.0.1:25565"
                    """.formatted(occupied.getLocalPort()));

            var result = capture(() -> StrataProxyLauncher.run(new String[] {config.toString()}));

            assertEquals(1, result.exitCode());
            assertTrue(result.error().contains("StrataProxy startup failed"));
        }
    }

    @Test
    void startsRuntimeFromYamlAndProxiesMinecraftStatus(@TempDir Path tempDir) throws Exception {
        var status = "{\"version\":{\"name\":\"StrataProxy smoke\",\"protocol\":763},\"players\":{\"online\":0,\"max\":100}}";
        var backendExecutor = Executors.newSingleThreadExecutor();
        try (var backend = new ServerSocket(0)) {
            backend.setSoTimeout(5_000);
            var backendFuture = backendExecutor.submit(() -> {
                try (var socket = backend.accept()) {
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

            var proxyPort = freePort();
            var config = tempDir.resolve("strataproxy.yml");
            Files.writeString(config, """
                    network:
                      bind: "127.0.0.1:%d"
                      workerThreads: 1
                      nativeTransport: false
                    registry:
                      persistenceEnabled: false
                      healthCheckEnabled: false
                    admin:
                      enabled: false
                    observability:
                      flushIntervalSeconds: 1
                    status:
                      enabled: false
                    servers:
                      - name: "smoke-backend"
                        address: "127.0.0.1:%d"
                        metadata:
                          host: "play.example.net"
                    """.formatted(proxyPort, backend.getLocalPort()));
            var loaded = new ConfigLoader().load(config);

            try (var runtime = StrataProxyLauncher.startRuntime(
                    loaded,
                    config,
                    new PrintStream(ByteArrayOutputStream.nullOutputStream(), true, StandardCharsets.UTF_8),
                    false)) {
                assertEquals(proxyPort, runtime.bindAddress().getPort());

                try (var client = new Socket()) {
                    client.connect(runtime.bindAddress(), 5_000);
                    client.setSoTimeout(5_000);
                    client.getOutputStream().write(statusHandshake(763, "play.example.net", proxyPort));
                    client.getOutputStream().write(statusRequest());
                    client.getOutputStream().flush();

                    assertEquals(status, readStatusResponse(client.getInputStream()));
                }
            }

            backendFuture.get(5, TimeUnit.SECONDS);
        } finally {
            backendExecutor.shutdownNow();
        }
    }

    @Test
    void startsRuntimeAndRespondsToMinecraftStatusWithoutBackend(@TempDir Path tempDir) throws Exception {
        var proxyPort = freePort();
        Files.write(tempDir.resolve("favicon.png"), new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
        var config = tempDir.resolve("strataproxy.yml");
        Files.writeString(config, """
                network:
                  bind: "127.0.0.1:%d"
                  workerThreads: 1
                  nativeTransport: false
                registry:
                  staticServers: false
                  persistenceEnabled: false
                  healthCheckEnabled: false
                admin:
                  enabled: false
                observability:
                  flushIntervalSeconds: 1
                status:
                  enabled: true
                  motd: "Maintenance window"
                  protocolName: "StrataProxy Ready"
                  protocolVersion: 763
                  maxPlayers: 500
                  faviconPath: "favicon.png"
                  samplePlayers:
                    - name: "Survival"
                      id: "00000000-0000-0000-0000-000000000001"
                    - name: "Modded"
                      id: "00000000-0000-0000-0000-000000000002"
                servers: []
                """.formatted(proxyPort));
        var loaded = new ConfigLoader().load(config);

        try (var runtime = StrataProxyLauncher.startRuntime(
                loaded,
                config,
                new PrintStream(ByteArrayOutputStream.nullOutputStream(), true, StandardCharsets.UTF_8),
                false)) {
            try (var client = new Socket()) {
                client.connect(runtime.bindAddress(), 5_000);
                client.setSoTimeout(5_000);
                client.getOutputStream().write(statusHandshake(763, "play.example.net", proxyPort));
                client.getOutputStream().write(statusRequest());
                client.getOutputStream().flush();

                var response = readStatusResponse(client.getInputStream());
                assertTrue(response.contains("\"name\":\"StrataProxy Ready\""));
                assertTrue(response.contains("\"protocol\":763"));
                assertTrue(response.contains("\"online\":0"));
                assertTrue(response.contains("\"max\":500"));
                assertTrue(response.contains("Maintenance window"));
                assertTrue(response.contains("\"favicon\":\"data:image/png;base64,iVBORw==\""));
                assertTrue(response.contains("\"sample\":[{\"name\":\"Survival\",\"id\":\"00000000-0000-0000-0000-000000000001\"},{\"name\":\"Modded\",\"id\":\"00000000-0000-0000-0000-000000000002\"}]"));

                client.getOutputStream().write(pingRequest(123456789L));
                client.getOutputStream().flush();
                assertEquals(123456789L, readPong(client.getInputStream()));
            }
        }
    }

    @Test
    void resolvesRelativeRegistryPersistencePathFromConfigDirectory(@TempDir Path tempDir) throws Exception {
        var configDirectory = tempDir.resolve("conf");
        Files.createDirectories(configDirectory);
        var config = configDirectory.resolve("strataproxy.yml");
        Files.writeString(config, """
                network:
                  bind: "127.0.0.1:0"
                  workerThreads: 1
                  nativeTransport: false
                registry:
                  persistenceEnabled: true
                  persistencePath: "data/registry.json"
                  healthCheckEnabled: false
                admin:
                  enabled: false
                observability:
                  flushIntervalSeconds: 1
                servers:
                  - name: "lobby-1"
                    address: "127.0.0.1:25565"
                """);
        var loaded = new ConfigLoader().load(config);

        try (var runtime = StrataProxyLauncher.startRuntime(
                loaded,
                config,
                new PrintStream(ByteArrayOutputStream.nullOutputStream(), true, StandardCharsets.UTF_8),
                false)) {
            assertTrue(runtime.bindAddress().getPort() > 0);
        }

        assertTrue(Files.exists(configDirectory.resolve("data").resolve("registry.json")));
    }

    @Test
    void runningRuntimeCloseIsIdempotentAndReleasesListener(@TempDir Path tempDir) throws Exception {
        var config = tempDir.resolve("strataproxy.yml");
        Files.writeString(config, """
                network:
                  bind: "127.0.0.1:0"
                  workerThreads: 1
                  nativeTransport: false
                registry:
                  persistenceEnabled: false
                  healthCheckEnabled: false
                admin:
                  enabled: false
                observability:
                  flushIntervalSeconds: 1
                servers:
                  - name: "lobby-1"
                    address: "127.0.0.1:25565"
                """);
        var loaded = new ConfigLoader().load(config);
        var runtime = StrataProxyLauncher.startRuntime(
                loaded,
                config,
                new PrintStream(ByteArrayOutputStream.nullOutputStream(), true, StandardCharsets.UTF_8),
                false);
        var address = runtime.bindAddress();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var stopped = executor.submit(() -> {
                runtime.awaitShutdown();
                return true;
            });

            runtime.close();
            runtime.close();

            assertTrue(stopped.get(5, TimeUnit.SECONDS));
        }

        try (var rebound = new ServerSocket()) {
            rebound.bind(address);
            assertEquals(address.getPort(), rebound.getLocalPort());
        }
    }

    @Test
    void quarantinesCorruptPersistedRegistryAndStarts(@TempDir Path tempDir) throws Exception {
        var configDirectory = tempDir.resolve("conf");
        var dataDirectory = configDirectory.resolve("data");
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve("registry.json"), "{ not-json");
        var config = configDirectory.resolve("strataproxy.yml");
        Files.writeString(config, """
                network:
                  bind: "127.0.0.1:0"
                  workerThreads: 1
                  nativeTransport: false
                registry:
                  persistenceEnabled: true
                  persistencePath: "data/registry.json"
                  healthCheckEnabled: false
                admin:
                  enabled: false
                observability:
                  flushIntervalSeconds: 1
                servers:
                  - name: "lobby-1"
                    address: "127.0.0.1:25565"
                """);
        var loaded = new ConfigLoader().load(config);
        var output = new ByteArrayOutputStream();

        try (var runtime = StrataProxyLauncher.startRuntime(
                loaded,
                config,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                false)) {
            assertTrue(runtime.bindAddress().getPort() > 0);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("WARN failed to load persisted registry"));
        assertTrue(Files.readString(dataDirectory.resolve("registry.json")).contains("lobby-1"));
        try (var files = Files.list(dataDirectory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("registry.json.invalid-")));
        }
    }

    private static Result capture(ThrowingIntSupplier supplier) throws Exception {
        var originalOut = System.out;
        var originalErr = System.err;
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            return new Result(
                    supplier.getAsInt(),
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] statusHandshake(int protocolVersion, String host, int port) {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0);
        writeVarInt(payload, protocolVersion);
        writeString(payload, host);
        payload.write((port >>> 8) & 0xFF);
        payload.write(port & 0xFF);
        writeVarInt(payload, 1);
        return frame(payload.toByteArray());
    }

    private static byte[] statusRequest() {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0);
        return frame(payload.toByteArray());
    }

    private static byte[] pingRequest(long value) {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, 1);
        for (var shift = 56; shift >= 0; shift -= 8) {
            payload.write((int) ((value >>> shift) & 0xFF));
        }
        return frame(payload.toByteArray());
    }

    private static byte[] statusResponse(String json) {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0);
        writeString(payload, json);
        return frame(payload.toByteArray());
    }

    private static String readStatusResponse(java.io.InputStream input) throws Exception {
        var data = new DataInputStream(input);
        var frame = data.readNBytes(readVarInt(data));
        var packet = new DataInputStream(new java.io.ByteArrayInputStream(frame));
        assertEquals(0, readVarInt(packet));
        var jsonBytes = packet.readNBytes(readVarInt(packet));
        return new String(jsonBytes, StandardCharsets.UTF_8);
    }

    private static long readPong(java.io.InputStream input) throws Exception {
        var data = new DataInputStream(input);
        var frame = data.readNBytes(readVarInt(data));
        var packet = new DataInputStream(new java.io.ByteArrayInputStream(frame));
        assertEquals(1, readVarInt(packet));
        return packet.readLong();
    }

    private static byte[] frame(byte[] payload) {
        var frame = new ByteArrayOutputStream();
        writeVarInt(frame, payload.length);
        frame.writeBytes(payload);
        return frame.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        var current = value;
        do {
            var temp = current & 0x7F;
            current >>>= 7;
            if (current != 0) {
                temp |= 0x80;
            }
            output.write(temp);
        } while (current != 0);
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

    private interface ThrowingIntSupplier {
        int getAsInt() throws Exception;
    }

    private record Result(int exitCode, String output, String error) {
    }
}

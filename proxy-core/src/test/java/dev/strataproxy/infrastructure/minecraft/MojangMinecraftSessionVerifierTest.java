package dev.strataproxy.infrastructure.minecraft;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MojangMinecraftSessionVerifierTest {
    @Test
    void allowsProfileReturnedByHasJoinedEndpoint() throws Exception {
        try (var server = localHasJoinedServer(200, "{\"id\":\"abc\",\"name\":\"PlayerOne\"}")) {
            var verifier = new MojangMinecraftSessionVerifier(
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/session/minecraft/hasJoined"),
                    Duration.ofSeconds(1));

            var result = verifier.verify("PlayerOne", "server-hash", null).toCompletableFuture().get();

            assertTrue(result.allowed());
        }
    }

    @Test
    void deniesMissingProfileFromHasJoinedEndpoint() throws Exception {
        try (var server = localHasJoinedServer(204, "")) {
            var verifier = new MojangMinecraftSessionVerifier(
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/session/minecraft/hasJoined"),
                    Duration.ofSeconds(1));

            var result = verifier.verify("PlayerOne", "server-hash", null).toCompletableFuture().get();

            assertFalse(result.allowed());
        }
    }

    private static LocalServer localHasJoinedServer(int status, String body) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/session/minecraft/hasJoined", exchange -> {
            var response = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        return new LocalServer(server);
    }

    private record LocalServer(HttpServer server) implements AutoCloseable {
        InetSocketAddress getAddress() {
            return server.getAddress();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

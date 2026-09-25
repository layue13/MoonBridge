package dev.strataproxy.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MojangSessionVerifierTest {
    @Test
    void oversizedResponseBodyIsRejectedBeforeParsing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = new byte[65 * 1024];
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        ExecutorService parser = Executors.newSingleThreadExecutor();
        try {
            var verifier = new MojangSessionVerifier(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                    Duration.ofSeconds(3), parser);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> verifier.verify("Player", "hash", null).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
        } finally {
            server.stop(0);
            parser.shutdownNow();
        }
    }

    @Test
    void partialResponseBodyDoesNotHoldVerifierPastRequestTimeout() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 128);
            try (var output = exchange.getResponseBody()) {
                output.write('{');
                output.flush();
                releaseBody.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        ExecutorService parser = Executors.newSingleThreadExecutor();
        try {
            var verifier = new MojangSessionVerifier(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                    Duration.ofMillis(300), parser);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> verifier.verify("Player", "hash", null).toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof TimeoutException);
            assertEquals("available", parser.submit(() -> "available").get(1, TimeUnit.SECONDS));
        } finally {
            releaseBody.countDown();
            server.stop(0);
            parser.shutdownNow();
        }
    }

    @Test
    void injectedEndpointPerformsAsyncHasJoinedAndReturnsCanonicalProfile() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> parsingThread = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session/minecraft/hasJoined", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = ("{\"id\":\"853c80ef3c3749fdaa49938b674adae6\",\"name\":\"Player\","
                    + "\"properties\":[{\"name\":\"textures\",\"value\":\"dGV4dHVyZQ==\",\"signature\":\"c2lnbmVk\"}]}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        ExecutorService parser = Executors.newSingleThreadExecutor(task -> new Thread(task, "session-json-parser"));
        try {
            SessionVerifier verifier = new MojangSessionVerifier(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/session/minecraft/hasJoined"),
                    Duration.ofSeconds(3), task -> parser.execute(() -> {
                        parsingThread.set(Thread.currentThread().getName());
                        task.run();
                    }));
            Optional<VerifiedProfile> result = verifier.verify("Player", "-hash+&", "2001:db8::1")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(result.isPresent());
            assertEquals("853c80ef-3c37-49fd-aa49-938b674adae6", result.orElseThrow().uuid().toString());
            assertEquals("Player", result.orElseThrow().username());
            assertEquals(java.util.List.of(new ProfileProperty("textures", "dGV4dHVyZQ==", "c2lnbmVk")),
                    result.orElseThrow().properties());
            assertTrue(query.get().contains("username=Player"));
            assertTrue(query.get().contains("serverId=-hash%2B%26"));
            assertTrue(query.get().contains("ip=2001%3Adb8%3A%3A1"));
            assertEquals("session-json-parser", parsingThread.get());
        } finally {
            server.stop(0);
            parser.shutdownNow();
        }
    }

    @Test
    void noJoinedSessionAndWrongUsernameReturnEmptyProfile() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<Integer> responseMode = new AtomicReference<>(204);
        server.createContext("/", exchange -> {
            int status = responseMode.get();
            byte[] body = status == 200
                    ? "{\"id\":\"853c80ef3c3749fdaa49938b674adae6\",\"name\":\"AnotherPlayer\"}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : new byte[0];
            if (body.length == 0) exchange.sendResponseHeaders(status, -1);
            else {
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            }
            exchange.close();
        });
        server.start();
        ExecutorService parser = Executors.newSingleThreadExecutor();
        try {
            MojangSessionVerifier verifier = new MojangSessionVerifier(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                    Duration.ofSeconds(3), parser);
            assertFalse(verifier.verify("Player", "hash", null).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).isPresent());
            responseMode.set(200);
            assertFalse(verifier.verify("Player", "hash", null).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).isPresent());
        } finally {
            server.stop(0);
            parser.shutdownNow();
        }
    }
}

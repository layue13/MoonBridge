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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MojangSessionVerifierTest {
    @Test
    void boundsConcurrentRequestsAndReleasesSlotsAfterCompletionAndCancellation() throws Exception {
        CountDownLatch firstRequestEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondRequestEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int requestNumber = requests.incrementAndGet();
            if (requestNumber == 1) {
                firstRequestEntered.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new IOException("test request was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test request interrupted", interrupted);
                }
            } else if (requestNumber == 2) {
                secondRequestEntered.countDown();
                try {
                    if (!releaseSecond.await(5, TimeUnit.SECONDS)) throw new IOException("test request was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test request interrupted", interrupted);
                }
            }
            byte[] body = "{\"id\":\"853c80ef3c3749fdaa49938b674adae6\",\"name\":\"Player\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        ExecutorService parser = Executors.newSingleThreadExecutor();
        try {
            var verifier = new MojangSessionVerifier(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                    Duration.ofSeconds(10), parser, 1);
            var first = verifier.verify("Player", "hash", null).toCompletableFuture();
            assertTrue(firstRequestEntered.await(5, TimeUnit.SECONDS));
            var rejected = verifier.verify("Player", "hash", null).toCompletableFuture();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> rejected.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof RejectedExecutionException);
            assertEquals(1, requests.get());

            releaseFirst.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isPresent());
            var cancelled = verifier.verify("Player", "hash", null).toCompletableFuture();
            assertTrue(secondRequestEntered.await(5, TimeUnit.SECONDS));
            assertTrue(cancelled.cancel(true));
            releaseSecond.countDown();
            java.util.concurrent.CompletableFuture<Optional<VerifiedProfile>> afterCancellation = null;
            for (int attempt = 0; attempt < 500 && afterCancellation == null; attempt++) {
                var next = verifier.verify("Player", "hash", null).toCompletableFuture();
                if (!next.isCompletedExceptionally()) afterCancellation = next;
                else Thread.sleep(10);
            }
            assertTrue(afterCancellation != null, "cancelling a request must free its verification slot");
            assertTrue(afterCancellation.get(5, TimeUnit.SECONDS).isPresent());
            assertEquals(3, requests.get());
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            server.stop(0);
            parser.shutdownNow();
        }
    }

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

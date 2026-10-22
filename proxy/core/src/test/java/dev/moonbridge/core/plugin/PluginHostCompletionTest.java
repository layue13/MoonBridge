package dev.moonbridge.core.plugin;

import dev.moonbridge.api.CommandCompletion;
import dev.moonbridge.api.CommandRegistration;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.api.Players;
import dev.moonbridge.api.MessageResult;
import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.TransferResult;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginHostCompletionTest {
    private static final PlayerView PLAYER = new PlayerView(
            new PlayerIdentity(UUID.randomUUID(), 1), "TestPlayer", Optional.empty());

    @Test
    void commandRootsAndArgumentCompletionPreserveWhitespaceAndBoundSuggestions() throws Exception {
        var request = new CompletableFuture<CommandCompletion>();
        var emptyArguments = new CompletableFuture<CommandCompletion>();
        var callbackThread = new AtomicReference<Thread>();
        var candidateList = new ArrayList<String>(Arrays.asList(
                "alpha", "alpha", null, "two words", "x".repeat(101), "猫".repeat(11_000), "beta"));
        for (int i = 0; i < 120; i++) candidateList.add("item" + i);
        CompletableFuture<List<String>> suggestions = CompletableFuture.completedFuture(candidateList);
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext value) {
                value.commands().register("Ping", ignored -> { }, completion -> {
                    callbackThread.set(Thread.currentThread());
                    if (completion.arguments().isEmpty()) emptyArguments.complete(completion);
                    else request.complete(completion);
                    return suggestions;
                });
                value.commands().register("alpha", ignored -> { });
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
            host.load(List.of(plugin));
            host.enable();

            assertEquals(List.of("/alpha", "/ping"), host.commandNames(""));
            assertEquals(List.of("/ping"), host.commandNames("P"));
            assertTrue(host.completeCommand(PLAYER, "/unknown arg").isEmpty());
            assertTrue(host.completeCommand(PLAYER, "/ping").isEmpty(), "root-only completion belongs to commandNames");

            CompletionStage<List<String>> stage = host.completeCommand(PLAYER, "/PiNg  first ").orElseThrow();
            assertEquals("ping", request.get(5, TimeUnit.SECONDS).commandName());
            assertEquals(" first ", request.get(5, TimeUnit.SECONDS).arguments());
            assertNotEquals(Thread.currentThread(), callbackThread.get(), "plugin completer must run on a worker");
            List<String> completed = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(List.of("alpha", "beta"), completed.subList(0, 2));
            assertEquals(100, completed.size());
            CompletionStage<List<String>> emptyStage = host.completeCommand(PLAYER, "/ping ").orElseThrow();
            assertEquals("", emptyArguments.get(5, TimeUnit.SECONDS).arguments());
            assertEquals(100, emptyStage.toCompletableFuture().get(5, TimeUnit.SECONDS).size());
            assertEquals(List.of(), host.completeCommand(PLAYER, "/alpha ").orElseThrow()
                    .toCompletableFuture().get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void closingHostCancelsPendingCompleterAndCompletesLocalResult() throws Exception {
        var invoked = new CountDownLatch(1);
        var pluginStageCancelled = new CountDownLatch(1);
        var pluginStage = new CompletableFuture<List<String>>();
        pluginStage.whenComplete((ignored, failure) -> pluginStageCancelled.countDown());
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.commands().register("wait", ignored -> { }, completion -> {
                    invoked.countDown();
                    return pluginStage;
                });
            }
        };
        var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1));
        host.load(List.of(plugin));
        host.enable();
        CompletableFuture<List<String>> local = host.completeCommand(PLAYER, "/wait ").orElseThrow()
                .toCompletableFuture();
        assertTrue(invoked.await(5, TimeUnit.SECONDS));

        host.close();

        ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> local.get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertTrue(pluginStageCancelled.await(1, TimeUnit.SECONDS));
        assertTrue(pluginStage.isCancelled(), "host close should cancel the plugin's unfinished stage");
    }

    @Test
    void timeoutCancelsPluginStageOffTheTimerThread() throws Exception {
        var invoked = new CountDownLatch(1);
        var cancellationCallback = new CountDownLatch(1);
        var callbackThread = new AtomicReference<Thread>();
        var pluginStage = new CompletableFuture<List<String>>();
        pluginStage.whenComplete((ignored, failure) -> {
            callbackThread.set(Thread.currentThread());
            cancellationCallback.countDown();
        });
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.commands().register("wait", ignored -> { }, completion -> {
                    invoked.countDown();
                    return pluginStage;
                });
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
            host.load(List.of(plugin));
            host.enable();
            CompletableFuture<List<String>> local = host.completeCommand(PLAYER, "/wait ").orElseThrow()
                    .toCompletableFuture();
            assertTrue(invoked.await(5, TimeUnit.SECONDS));
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> local.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof java.util.concurrent.TimeoutException);
            assertTrue(cancellationCallback.await(1, TimeUnit.SECONDS));
            assertTrue(pluginStage.isCancelled());
            assertNotEquals(Thread.currentThread(), callbackThread.get());
            assertTrue(callbackThread.get().getName().startsWith("moonbridge-plugin-player-"));
        }
    }

    @Test
    void unregisterCancelsPendingCompletionAndRemovesTheRoot() throws Exception {
        var invoked = new CountDownLatch(1);
        var cancellationCallback = new CountDownLatch(1);
        var pluginStage = new CompletableFuture<List<String>>();
        pluginStage.whenComplete((ignored, failure) -> cancellationCallback.countDown());
        var registration = new AtomicReference<CommandRegistration>();
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                registration.set(context.commands().register("wait", ignored -> { }, completion -> {
                    invoked.countDown();
                    return pluginStage;
                }));
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
            host.load(List.of(plugin));
            host.enable();
            CompletableFuture<List<String>> local = host.completeCommand(PLAYER, "/wait ").orElseThrow()
                    .toCompletableFuture();
            assertTrue(invoked.await(5, TimeUnit.SECONDS));

            registration.get().unregister();

            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> local.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(cancellationCallback.await(1, TimeUnit.SECONDS));
            assertTrue(pluginStage.isCancelled());
            assertEquals(List.of(), host.commandNames("wait"));
            assertTrue(host.completeCommand(PLAYER, "/wait ").isEmpty());
        }
    }

    @Test
    void completerThrowAndNullStageFailPromptly() throws Exception {
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.commands().register("throws", ignored -> { }, completion -> {
                    throw new IllegalArgumentException("bad completer");
                });
                context.commands().register("nullstage", ignored -> { }, completion -> null);
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
            host.load(List.of(plugin));
            host.enable();
            for (String command : List.of("throws", "nullstage")) {
                CompletableFuture<List<String>> local = host.completeCommand(PLAYER, "/" + command + " ")
                        .orElseThrow().toCompletableFuture();
                ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                        () -> local.get(500, TimeUnit.MILLISECONDS));
                assertFalse(failure.getCause() instanceof java.util.concurrent.TimeoutException);
            }
        }
    }

    private static Players players() {
        return new Players() {
            @Override public Optional<PlayerView> find(PlayerIdentity identity) { return Optional.empty(); }
            @Override public List<PlayerView> online() { return List.of(); }
            @Override public CompletionStage<TransferResult> transfer(PlayerIdentity identity, String backendName) {
                return CompletableFuture.completedFuture(TransferResult.failed("unused"));
            }
            @Override public CompletionStage<MessageResult> sendMessage(PlayerIdentity identity, net.kyori.adventure.text.Component message) {
                return CompletableFuture.completedFuture(MessageResult.NOT_CONNECTED);
            }
            @Override public CompletionStage<DisconnectResult> disconnect(PlayerIdentity identity, net.kyori.adventure.text.Component reason) {
                return CompletableFuture.completedFuture(DisconnectResult.NOT_CONNECTED);
            }
        };
    }
}

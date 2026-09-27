package dev.moonbridge.core.plugin;

import dev.moonbridge.api.*;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.core.control.BackendChannelTransport;
import dev.moonbridge.messaging.*;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PluginHostMessagingTest {
    private static final String CHANNEL = "proxy:events";

    @Test void pluginContextMessagingRoutesLocallyAndCombinesProxyPublishWithTransport() throws Exception {
        AtomicReference<PluginContext> contextRef = new AtomicReference<>();
        CopyOnWriteArrayList<Message> events = new CopyOnWriteArrayList<>();
        AtomicReference<CompletionStage<Message>> localRequest = new AtomicReference<>();
        AtomicReference<CompletionStage<SendReceipt>> localSend = new AtomicReference<>();
        AtomicReference<CompletionStage<PublishResult>> publication = new AtomicReference<>();
        var plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                contextRef.set(context);
                context.messaging().channel(CHANNEL).subscribe(events::add);
                context.messaging().channel(CHANNEL).onRequest(request ->
                        CompletableFuture.completedFuture(request.payload()));
            }
            @Override public void onEnable() {
                var channel = contextRef.get().messaging().channel(CHANNEL);
                localRequest.set(channel.request(Endpoint.proxy(), new byte[]{3}));
                localSend.set(channel.send(Endpoint.proxy(), new byte[]{4}));
                publication.set(channel.publish(new byte[]{5}));
            }
        };
        BackendChannelTransport transport = new BackendChannelTransport() {
            @Override public CompletionStage<SendResult> send(Message message) {
                return CompletableFuture.completedFuture(SendResult.ACCEPTED);
            }
            @Override public CompletionStage<Message> request(Message message, Duration timeout) {
                return CompletableFuture.failedFuture(new AssertionError("unexpected remote request"));
            }
            @Override public CompletionStage<PublishResult> publish(Message event) {
                return CompletableFuture.completedFuture(new PublishResult(event.id(),
                        Map.of(Endpoint.backend("remote"), SendResult.ACCEPTED)));
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), noPlayers(), Duration.ofSeconds(1))) {
            host.setBackendChannelTransport(transport);
            host.load(List.of(plugin));
            host.enable();
            Message reply = localRequest.get().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(Endpoint.proxy(), reply.source());
            assertEquals(Endpoint.proxy(), reply.target());
            assertArrayEquals(new byte[]{3}, reply.payload());
            assertEquals(SendResult.ACCEPTED,
                    localSend.get().toCompletableFuture().get(2, TimeUnit.SECONDS).result());
            PublishResult result = publication.get().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(Map.of(Endpoint.proxy(), SendResult.ACCEPTED,
                    Endpoint.backend("remote"), SendResult.ACCEPTED), result.results());
            await(() -> events.size() == 2);
            assertEquals(2, events.size(), "local direct send and publish each dispatch once");
        }
    }

    @Test void disablingHostCompletesPluginOwnedPendingProxyRequest() throws Exception {
        AtomicReference<CompletionStage<Message>> pending = new AtomicReference<>();
        Plugin actual = new Plugin() {
            private PluginContext context;
            @Override public void onLoad(PluginContext value) {
                context = value;
                value.messaging().channel(CHANNEL).onRequest(request -> new CompletableFuture<>());
            }
            @Override public void onEnable() {
                pending.set(context.messaging().channel(CHANNEL)
                        .request(Endpoint.proxy(), new byte[0], Duration.ofSeconds(30)));
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), noPlayers(), Duration.ofSeconds(1))) {
            host.load(List.of(actual));
            host.enable();
            assertNotNull(pending.get());
            host.close();
            var failure = assertThrows(ExecutionException.class,
                    () -> pending.get().toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertInstanceOf(MessagingException.class, failure.getCause());
            assertEquals(MessagingException.Code.CLOSED, ((MessagingException) failure.getCause()).code());
        }
    }

    private static Players noPlayers() {
        return new Players() {
            @Override public Optional<PlayerView> find(PlayerIdentity identity) { return Optional.empty(); }
            @Override public List<PlayerView> online() { return List.of(); }
            @Override public CompletionStage<TransferResult> transfer(PlayerIdentity identity, String backendName) {
                return CompletableFuture.completedFuture(TransferResult.failed("unavailable"));
            }
            @Override public CompletionStage<MessageResult> sendMessage(PlayerIdentity identity, String message) {
                return CompletableFuture.completedFuture(MessageResult.NOT_CONNECTED);
            }
            @Override public CompletionStage<MessageResult> sendMessage(PlayerIdentity identity, Component message) {
                return CompletableFuture.completedFuture(MessageResult.NOT_CONNECTED);
            }
            @Override public CompletionStage<DisconnectResult> disconnect(PlayerIdentity identity, String reason) {
                return CompletableFuture.completedFuture(DisconnectResult.NOT_CONNECTED);
            }
            @Override public CompletionStage<DisconnectResult> disconnect(PlayerIdentity identity, Component reason) {
                return CompletableFuture.completedFuture(DisconnectResult.NOT_CONNECTED);
            }
        };
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("condition not met");
            Thread.sleep(5);
        }
    }
}

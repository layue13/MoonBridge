package dev.moonbridge.core.plugin;

import dev.moonbridge.api.AccessDecision;
import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.MessageResult;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.Players;
import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.api.TransferResult;
import dev.moonbridge.api.event.PlayerCommandEvent;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerCommandEventTest {
    private static final PlayerView PLAYER = new PlayerView(
            new PlayerIdentity(UUID.randomUUID(), 1), "Tester", Optional.empty());

    @Test
    void allowedCommandRunsAndListenerSeesNameAndArguments() throws Exception {
        var seen = new LinkedBlockingQueue<PlayerCommandEvent>();
        var ran = new LinkedBlockingQueue<String>();
        var plugin = plugin(event -> {
            seen.add(event);
            return CompletableFuture.completedFuture(AccessDecision.allow());
        }, ran);
        try (var host = host(plugin)) {
            assertTrue(host.dispatchCommand(PLAYER, "/Island home  2", ignored -> { }));
            assertEquals("home  2", ran.poll(2, TimeUnit.SECONDS));
            PlayerCommandEvent event = seen.poll(2, TimeUnit.SECONDS);
            assertEquals("island", event.command());
            assertEquals("home  2", event.arguments());
            assertEquals(PLAYER, event.player());
        }
    }

    @Test
    void deniedCommandDoesNotRunAndTellsThePlayerWhy() throws Exception {
        var ran = new LinkedBlockingQueue<String>();
        var replies = new LinkedBlockingQueue<Component>();
        var plugin = plugin(event -> CompletableFuture.completedFuture(AccessDecision.deny("Log in first.")), ran);
        try (var host = host(plugin)) {
            assertTrue(host.dispatchCommand(PLAYER, "/island home", replies::add));
            assertEquals("Log in first.", ((TextComponent) replies.poll(2, TimeUnit.SECONDS)).content());
            assertNull(ran.poll(300, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void failingListenerDeniesTheCommand() throws Exception {
        var ran = new LinkedBlockingQueue<String>();
        var replies = new LinkedBlockingQueue<Component>();
        var plugin = plugin(event -> CompletableFuture.failedFuture(new IllegalStateException("db down")), ran);
        try (var host = host(plugin)) {
            assertTrue(host.dispatchCommand(PLAYER, "/island", replies::add));
            assertEquals("This command is not available right now.",
                    ((TextComponent) replies.poll(2, TimeUnit.SECONDS)).content());
            assertNull(ran.poll(300, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void unknownCommandsAreNotClaimedOrChecked() throws Exception {
        var seen = new LinkedBlockingQueue<PlayerCommandEvent>();
        var plugin = plugin(event -> {
            seen.add(event);
            return CompletableFuture.completedFuture(AccessDecision.deny("no"));
        }, new LinkedBlockingQueue<>());
        try (var host = host(plugin)) {
            assertFalse(host.dispatchCommand(PLAYER, "/login secret", ignored -> { }));
            assertNull(seen.poll(300, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void deniedCommandsGetNoArgumentCompletions() throws Exception {
        var plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(PlayerCommandEvent.class, event ->
                        CompletableFuture.completedFuture(AccessDecision.deny("Log in first.")));
                context.commands().register("island", invocation -> { },
                        completion -> CompletableFuture.completedFuture(List.of("secret-island")));
            }
        };
        try (var host = host(plugin)) {
            assertEquals(List.of(), host.completeCommand(PLAYER, "/island ").orElseThrow()
                    .toCompletableFuture().get(2, TimeUnit.SECONDS));
        }
    }

    private static PluginHost host(Plugin plugin) {
        var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1));
        host.load(List.of(plugin));
        host.enable();
        assertTrue(host.hasSubscribers(PlayerCommandEvent.class));
        return host;
    }

    private static Plugin plugin(Function<PlayerCommandEvent, CompletionStage<AccessDecision>> gate,
                                 LinkedBlockingQueue<String> ran) {
        return new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(PlayerCommandEvent.class, gate::apply);
                context.commands().register("island", invocation -> ran.add(invocation.arguments()));
            }
        };
    }

    private static Players players() {
        return new Players() {
            @Override public Optional<PlayerView> find(PlayerIdentity identity) {
                return PLAYER.identity().equals(identity) ? Optional.of(PLAYER) : Optional.empty();
            }
            @Override public List<PlayerView> online() { return List.of(PLAYER); }
            @Override public CompletableFuture<TransferResult> transfer(PlayerIdentity identity, String backendName) {
                return CompletableFuture.completedFuture(TransferResult.failed("test"));
            }
            @Override public CompletableFuture<MessageResult> sendMessage(PlayerIdentity identity, Component message) {
                return CompletableFuture.completedFuture(MessageResult.NOT_CONNECTED);
            }
            @Override public CompletableFuture<DisconnectResult> disconnect(PlayerIdentity identity, Component reason) {
                return CompletableFuture.completedFuture(DisconnectResult.NOT_CONNECTED);
            }
        };
    }
}

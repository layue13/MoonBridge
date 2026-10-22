package dev.moonbridge.core.plugin;

import dev.moonbridge.api.CommandRegistrationOptions;
import dev.moonbridge.api.CommandSource;
import dev.moonbridge.api.Commands;
import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.MessageResult;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.Players;
import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.api.TransferResult;
import dev.moonbridge.api.permission.PermissionDecision;
import dev.moonbridge.api.permission.PermissionProvider;
import dev.moonbridge.api.permission.PermissionResult;
import dev.moonbridge.api.permission.PermissionSubject;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCommandTest {
    private static final PlayerView PLAYER = new PlayerView(
            new PlayerIdentity(UUID.randomUUID(), 1), "Tester", Optional.empty());

    @Test
    void permissionRequirementFiltersRootsCompletionAndExecution() throws Exception {
        var executions = new AtomicInteger();
        var completions = new AtomicInteger();
        var denied = new CountDownLatch(1);
        var commands = new AtomicReference<Commands>();
        var plugin = new PermissionPlugin(context -> {
            commands.set(context.commands());
            context.commands().register("staff", "moonbridge.staff", invocation -> executions.incrementAndGet(),
                    completion -> {
                        completions.incrementAndGet();
                        return CompletableFuture.completedFuture(List.of("secret"));
                    });
        }, node -> PermissionDecision.DENY);

        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
            host.load(List.of(plugin));
            host.enable();
            host.permissionService().prepare(PLAYER).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertTrue(host.commandNames(PLAYER, "st").isEmpty());
            assertFalse(host.commandVisible(PLAYER, "/staff"));
            assertTrue(host.commandVisible(PLAYER, "/backend-only"));
            assertEquals(List.of(), host.completeCommand(PLAYER, "/staff ").orElseThrow()
                    .toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(0, completions.get());
            assertTrue(host.dispatchCommand(PLAYER, "/staff", component -> denied.countDown()));
            assertTrue(denied.await(2, TimeUnit.SECONDS));
            assertTrue(commands.get().execute(CommandSource.player(PLAYER, ignored -> { }), "/staff")
                    .toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(0, executions.get());
        }
    }

    @Test
    void explicitConsoleCommandsDoNotInventPlayerPermissionSubjects() throws Exception {
        var playerDenied = new CountDownLatch(1);
        var consoleExecuted = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var plugin = new PermissionPlugin(context -> context.commands().register("staff",
                CommandRegistrationOptions.requiring("moonbridge.staff").withConsole(true), invocation -> {
                    executions.incrementAndGet();
                    if (invocation.source().player().isEmpty()) consoleExecuted.countDown();
                }), node -> PermissionDecision.DENY);

        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
            host.load(List.of(plugin));
            host.enable();
            host.permissionService().prepare(PLAYER).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertTrue(host.dispatchCommand(PLAYER, "/staff", component -> playerDenied.countDown()));
            assertTrue(playerDenied.await(2, TimeUnit.SECONDS));
            assertTrue(host.dispatchConsoleCommand("staff", ignored -> { }));
            assertTrue(consoleExecuted.await(2, TimeUnit.SECONDS));
            assertEquals(1, executions.get());
            assertFalse(host.dispatchConsoleCommand("missing", ignored -> { }));
        }
    }

    @Test
    void undefinedAndUnavailablePermissionsDoNotRunProtectedCommands() throws Exception {
        for (PermissionResult expected : List.of(PermissionResult.UNDEFINED, PermissionResult.UNAVAILABLE)) {
            var executions = new AtomicInteger();
            var denied = new CountDownLatch(1);
            var permissions = new AtomicReference<dev.moonbridge.api.permission.Permissions>();
            var plugin = new PermissionPlugin(context -> {
                permissions.set(context.permissions());
                context.commands().register("staff", "moonbridge.staff", invocation -> executions.incrementAndGet());
            }, node -> PermissionDecision.UNDEFINED, expected != PermissionResult.UNAVAILABLE);

            try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
                host.load(List.of(plugin));
                host.enable();
                host.permissionService().prepare(PLAYER).toCompletableFuture().get(2, TimeUnit.SECONDS);

                assertEquals(expected, host.permissionService().check(PLAYER.identity(), "moonbridge.staff"));
                assertFalse(permissions.get().hasPermission(PLAYER.identity(), "moonbridge.staff"));
                assertTrue(host.dispatchCommand(PLAYER, "/staff", component -> denied.countDown()));
                assertTrue(denied.await(2, TimeUnit.SECONDS));
                assertEquals(0, executions.get());
            }
        }
    }

    @Test
    void permissionRevokedWhileArgumentCompletionIsPendingReturnsNoSuggestions() throws Exception {
        var decision = new AtomicReference<>(PermissionDecision.ALLOW);
        var completionStarted = new CountDownLatch(1);
        var suggestions = new CompletableFuture<List<String>>();
        var plugin = new PermissionPlugin(context -> context.commands().register("staff", "moonbridge.staff",
                invocation -> { }, completion -> {
                    completionStarted.countDown();
                    return suggestions;
                }), node -> decision.get());

        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
            host.load(List.of(plugin));
            host.enable();
            host.permissionService().prepare(PLAYER).toCompletableFuture().get(2, TimeUnit.SECONDS);

            var result = host.completeCommand(PLAYER, "/staff ").orElseThrow().toCompletableFuture();
            assertTrue(completionStarted.await(2, TimeUnit.SECONDS));
            decision.set(PermissionDecision.DENY);
            suggestions.complete(List.of("private-target"));
            assertEquals(List.of(), result.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void multiplePermissionProvidersFailStartupInsteadOfSelectingByLoadOrder() {
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
            host.load(List.of(new PermissionPlugin(ignored -> { }, node -> PermissionDecision.ALLOW),
                    new PermissionPlugin(ignored -> { }, node -> PermissionDecision.ALLOW)));
            var failure = assertThrows(IllegalStateException.class, host::enable);
            assertTrue(failure.getMessage().contains("Only one plugin may provide player permissions"));
        }
    }

    @Test
    void apiDispatchResolvesExactLivePlayerAndExpiresWithPluginContext() throws Exception {
        var commands = new AtomicReference<Commands>();
        var invocationSource = new AtomicReference<PlayerView>();
        var executed = new CountDownLatch(1);
        var plugin = new PermissionPlugin(context -> {
            commands.set(context.commands());
            context.commands().register("staff", "moonbridge.staff", invocation -> {
                invocationSource.set(invocation.player());
                executed.countDown();
            });
        }, node -> PermissionDecision.ALLOW);

        PluginHost host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1));
        try {
            host.load(List.of(plugin));
            host.enable();
            host.permissionService().prepare(PLAYER).toCompletableFuture().get(2, TimeUnit.SECONDS);

            var source = CommandSource.player(PLAYER, ignored -> { });
            assertFalse(commands.get().execute(source, "missing").toCompletableFuture().get(2, TimeUnit.SECONDS));
            var staleReply = new CountDownLatch(1);
            var stalePlayer = new PlayerView(new PlayerIdentity(PLAYER.identity().playerId(), 2), "Tester",
                    Optional.empty());
            assertTrue(commands.get().execute(CommandSource.player(stalePlayer, ignored -> staleReply.countDown()),
                    "staff").toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(staleReply.await(2, TimeUnit.SECONDS));
            assertNull(invocationSource.get());
            var brokenReplySource = CommandSource.player(stalePlayer,
                    ignored -> { throw new IllegalStateException("reply channel failed"); });
            var failedDispatch = commands.get().execute(brokenReplySource, "staff").toCompletableFuture();
            var replyFailure = assertThrows(ExecutionException.class,
                    () -> failedDispatch.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, replyFailure.getCause());
            assertTrue(commands.get().execute(source, "/staff").toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(executed.await(2, TimeUnit.SECONDS));
            assertEquals(PLAYER, invocationSource.get());
        } finally {
            host.close();
        }
        assertThrows(IllegalStateException.class,
                () -> commands.get().execute(CommandSource.console(ignored -> { }), "staff"));
    }

    @Test
    void asyncCommandsCanAwaitNestedDispatchWithoutOccupyingWorkers() throws Exception {
        var commands = new AtomicReference<Commands>();
        var childStarted = new CountDownLatch(2);
        var childCompletion = new CompletableFuture<Void>();
        var plugin = new PermissionPlugin(context -> {
            commands.set(context.commands());
            var console = CommandRegistrationOptions.defaults().withConsole(true);
            context.commands().registerAsync("child", console, invocation -> {
                childStarted.countDown();
                return childCompletion;
            }, null);
            context.commands().registerAsync("parent", console,
                    invocation -> context.commands().execute(CommandSource.console(ignored -> { }), "child")
                            .thenApply(ignored -> null), null);
        }, node -> PermissionDecision.ALLOW);

        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1), 1, 128)) {
            host.load(List.of(plugin));
            host.enable();
            host.permissionService().prepare(PLAYER).toCompletableFuture().get(2, TimeUnit.SECONDS);

            var first = commands.get().execute(CommandSource.console(ignored -> { }), "parent")
                    .toCompletableFuture();
            var second = commands.get().execute(CommandSource.console(ignored -> { }), "parent")
                    .toCompletableFuture();

            assertTrue(childStarted.await(2, TimeUnit.SECONDS), "both nested child handlers should start");
            assertFalse(first.isDone(), "parent dispatch must await its nested asynchronous child");
            assertFalse(second.isDone(), "parent dispatch must await its nested asynchronous child");

            childCompletion.complete(null);
            assertTrue(first.get(2, TimeUnit.SECONDS));
            assertTrue(second.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void asyncCommandLimitReleasesOnCompletionAndCloseSettlesLateStages() throws Exception {
        var commands = new AtomicReference<Commands>();
        var handlers = new ConcurrentLinkedQueue<CompletableFuture<Void>>();
        var started = new CountDownLatch(128);
        var additionalStarted = new CountDownLatch(1);
        var handlerCount = new AtomicInteger();
        var plugin = new PermissionPlugin(context -> {
            commands.set(context.commands());
            context.commands().registerAsync("pending", CommandRegistrationOptions.defaults().withConsole(true),
                    invocation -> {
                        var completion = new CompletableFuture<Void>();
                        handlers.add(completion);
                        if (handlerCount.incrementAndGet() <= 128) started.countDown();
                        else additionalStarted.countDown();
                        return completion;
                    }, null);
        }, node -> PermissionDecision.ALLOW);
        var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1), 1, 128);
        try {
            host.load(List.of(plugin));
            host.enable();
            host.permissionService().prepare(PLAYER).toCompletableFuture().get(2, TimeUnit.SECONDS);

            var calls = new java.util.ArrayList<CompletableFuture<Boolean>>();
            for (int i = 0; i < 128; i++) {
                calls.add(commands.get().execute(CommandSource.console(ignored -> { }), "pending")
                        .toCompletableFuture());
            }
            assertTrue(started.await(3, TimeUnit.SECONDS),
                    "all admitted handlers should reach their incomplete stages");
            var rejected = commands.get().execute(CommandSource.console(ignored -> { }), "pending")
                    .toCompletableFuture();
            assertThrows(ExecutionException.class, () -> rejected.get(2, TimeUnit.SECONDS));

            handlers.peek().complete(null);
            assertTrue(calls.get(0).get(2, TimeUnit.SECONDS));
            var afterRelease = commands.get().execute(CommandSource.console(ignored -> { }), "pending")
                    .toCompletableFuture();
            calls.add(afterRelease);
            assertTrue(additionalStarted.await(3, TimeUnit.SECONDS),
                    "the newly available slot should admit another handler");

            host.close();
            for (int index = 1; index < calls.size(); index++) {
                assertTrue(calls.get(index).isCompletedExceptionally(), "close should settle every pending dispatch");
            }
            handlers.forEach(stage -> stage.complete(null));
            assertTrue(calls.get(0).get(2, TimeUnit.SECONDS));
            for (int index = 1; index < calls.size(); index++) {
                assertTrue(calls.get(index).isCompletedExceptionally(),
                        "late stage completion must not replace shutdown settlement");
            }
        } finally {
            host.close();
        }
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

    @FunctionalInterface
    private interface Configure {
        void apply(PluginContext context);
    }

    @FunctionalInterface
    private interface Decision {
        PermissionDecision check(String node);
    }

    private record PermissionPlugin(Configure configure, Decision decision, boolean providesPermissions) implements Plugin {
        private PermissionPlugin(Configure configure, Decision decision) {
            this(configure, decision, true);
        }

        @Override public void onLoad(PluginContext context) { configure.apply(context); }

        @Override public Optional<PermissionProvider> permissionProvider() {
            if (!providesPermissions) return Optional.empty();
            return Optional.of(player -> CompletableFuture.completedFuture(new PermissionSubject() {
                @Override public PermissionDecision check(String node,
                        dev.moonbridge.api.permission.PermissionContext context) {
                    return decision.check(node);
                }
                @Override public void update(PlayerView player) { }
                @Override public void close() { }
            }));
        }
    }
}

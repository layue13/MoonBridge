import dev.moonbridge.api.*;
import dev.moonbridge.api.permission.*;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.core.plugin.PluginHost;
import dev.moonbridge.core.protocol.MinecraftText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Runs against the assembled distribution and its actual isolated LuckPerms plugin JAR. */
public final class PermissionAcceptance {
    private static final String PROVIDER = "dev.moonbridge.luckperms.LuckPermsMoonBridgePlugin";
    private static final UUID UUID_VALUE = UUID.fromString("b738c282-30a0-4ea5-89e4-7f4b492b8871");
    private static final String USER = "lp user " + UUID_VALUE + " ";

    public static void main(String[] args) throws Exception {
        var backgroundFailures = new CopyOnWriteArrayList<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            backgroundFailures.add(failure);
            failure.printStackTrace();
        });
        if (args.length != 2) throw new IllegalArgumentException("Usage: PermissionAcceptance <distribution> <evidence parent directory>");
        Path distribution = Path.of(args[0]).toAbsolutePath();
        Path evidenceRoot = Files.createDirectories(Path.of(args[1]).toAbsolutePath());
        Path evidence = Files.createTempDirectory(evidenceRoot, "run-");
        Path plugins = Files.createDirectories(evidence.resolve("plugins"));
        try (var files = Files.list(distribution.resolve("plugins"))) {
            Path jar = files.filter(file -> file.getFileName().toString().startsWith("luckperms-moonbridge-")
                    && file.toString().endsWith(".jar") && !file.toString().endsWith("-engine.jar")).findFirst().orElseThrow();
            Files.copy(jar, plugins.resolve(jar.getFileName()));
        }
        Path data = Files.createDirectories(plugins.resolve("data").resolve(PROVIDER));
        Files.writeString(data.resolve("config.yml"), "server: acceptance-proxy\nstorage-method: h2\nmessaging-service: none\n");

        var output = new CopyOnWriteArrayList<String>();
        var players = new TestPlayers(output);
        PlayerView first = new PlayerView(new PlayerIdentity(UUID_VALUE, 1), "PermProbe", "lobby");
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players, Duration.ofSeconds(5))) {
            Probe probe = start(host, plugins);
            players.current = first;
            host.permissionService().prepare(first).toCompletableFuture().get(30, TimeUnit.SECONDS);
            expect(probe, first, "acceptance.unknown", PermissionResult.UNDEFINED);

            command(probe, output, USER + "permission set acceptance.global true");
            expect(probe, first, "acceptance.global", PermissionResult.ALLOW);
            command(probe, output, USER + "permission set acceptance.global false");
            expect(probe, first, "acceptance.global", PermissionResult.DENY);
            command(probe, output, USER + "permission unset acceptance.global");
            expect(probe, first, "acceptance.global", PermissionResult.UNDEFINED);

            command(probe, output, "lp creategroup acceptance");
            command(probe, output, "lp group acceptance permission set acceptance.inherited true");
            command(probe, output, USER + "parent add acceptance");
            expect(probe, first, "acceptance.inherited", PermissionResult.ALLOW);
            command(probe, output, USER + "permission set acceptance.inherited false");
            expect(probe, first, "acceptance.inherited", PermissionResult.DENY);
            command(probe, output, USER + "permission unset acceptance.inherited");
            expect(probe, first, "acceptance.inherited", PermissionResult.ALLOW);

            command(probe, output, USER + "permission set acceptance.context true world=lobby");
            command(probe, output, USER + "permission set acceptance.proxy true server=acceptance-proxy");
            expect(probe, first, "acceptance.context", PermissionResult.ALLOW);
            expect(probe, first, "acceptance.proxy", PermissionResult.ALLOW);
            require(probe.context.permissions().check(first.identity(), "acceptance.context",
                    PermissionContext.empty().with("backend", "games")) == PermissionResult.UNDEFINED,
                    "proposed backend must override current world context");
            PlayerView transferred = new PlayerView(first.identity(), first.username(), "games");
            players.current = transferred;
            host.permissionService().update(transferred);
            expect(probe, transferred, "acceptance.context", PermissionResult.UNDEFINED);
            expect(probe, transferred, "acceptance.proxy", PermissionResult.ALLOW);

            command(probe, output, USER + "permission settemp acceptance.temporary true 2s");
            expect(probe, transferred, "acceptance.temporary", PermissionResult.ALLOW);
            await(() -> probe.context.permissions().check(transferred.identity(), "acceptance.temporary")
                    == PermissionResult.UNDEFINED, "temporary permission expiry");

            require(probe.context.commands().execute(CommandSource.player(transferred, message -> capture(output, message)),
                    USER + "permission set acceptance.escalation true").toCompletableFuture().get(30, TimeUnit.SECONDS),
                    "LuckPerms player command must be consumed");
            expect(probe, transferred, "acceptance.escalation", PermissionResult.UNDEFINED);
            command(probe, output, USER + "permission set luckperms.* true");
            expect(probe, transferred, "luckperms.user.permission.set", PermissionResult.ALLOW);
            require(!host.completeCommand(transferred, "/lp ").orElseThrow().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).isEmpty(), "LuckPerms native command completion");
            command(probe, output, "lp info");
            command(probe, output, USER + "permission info");

            PlayerView reconnect = new PlayerView(new PlayerIdentity(UUID_VALUE, 2), first.username(), "lobby");
            players.current = reconnect;
            host.permissionService().prepare(reconnect).toCompletableFuture().get(30, TimeUnit.SECONDS);
            // Intentionally overlap ownership: a delayed old disconnect must not evict the new user.
            host.permissionService().release(first.identity());
            require(probe.context.permissions().check(first.identity(), "acceptance.inherited") == PermissionResult.UNAVAILABLE,
                    "old connection must be unavailable");
            expect(probe, reconnect, "acceptance.inherited", PermissionResult.ALLOW);
            host.permissionService().release(reconnect.identity());
            players.current = null;
        }

        // A fresh plugin class loader and real H2 reopen must retain users, groups and inherited grants.
        try (var restarted = new PluginHost(new InMemoryBackendCatalog(), players, Duration.ofSeconds(5))) {
            Probe probe = start(restarted, plugins);
            PlayerView returning = new PlayerView(new PlayerIdentity(UUID_VALUE, 3), "PermProbe", "lobby");
            players.current = returning;
            restarted.permissionService().prepare(returning).toCompletableFuture().get(30, TimeUnit.SECONDS);
            expect(probe, returning, "acceptance.inherited", PermissionResult.ALLOW);
            expect(probe, returning, "acceptance.context", PermissionResult.ALLOW);
            expect(probe, returning, "acceptance.proxy", PermissionResult.ALLOW);
            restarted.permissionService().release(returning.identity());
            players.current = null;
        }
        // A malformed config must fail startup and still release work started during onLoad.
        Path configuration = data.resolve("config.yml");
        String validConfiguration = Files.readString(configuration);
        Files.writeString(configuration, "server: [\n");
        try (var broken = new PluginHost(new InMemoryBackendCatalog(), players, Duration.ofSeconds(5))) {
            try {
                start(broken, plugins);
                throw new AssertionError("malformed LuckPerms config must reject plugin startup");
            } catch (java.io.IOException expected) {
                output.add("Malformed config rejected: " + expected.getClass().getSimpleName());
            }
        } finally {
            Files.writeString(configuration, validConfiguration);
        }
        // Catch work which survives plugin shutdown, including HTTP/event generator threads.
        Thread.sleep(500);
        require(backgroundFailures.isEmpty(), "background plugin failures: " + backgroundFailures);
        Files.write(evidence.resolve("command-output.txt"), output);
        Files.writeString(evidence.resolve("result.txt"), "PERMISSION_ACCEPTANCE_PASS\n");
        System.out.println("PERMISSION_ACCEPTANCE_PASS " + evidence);
    }

    private static Probe start(PluginHost host, Path directory) throws Exception {
        Probe probe = new Probe();
        host.load(List.of(probe));
        host.loadPlugins(directory, Map.of(PROVIDER, Map.of("proxy-id", "acceptance-proxy")));
        host.enable();
        return probe;
    }

    private static void command(Probe probe, List<String> output, String command) throws Exception {
        System.out.println("COMMAND " + command);
        require(probe.context.commands().execute(CommandSource.console(message -> capture(output, message)), command)
                .toCompletableFuture().get(30, TimeUnit.SECONDS), "registered command: " + command);
    }

    private static void expect(Probe probe, PlayerView player, String node, PermissionResult expected) throws Exception {
        await(() -> probe.context.permissions().check(player.identity(), node) == expected, node + " = " + expected);
    }

    private static void await(java.util.function.BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError(description);
            Thread.sleep(25);
        }
    }

    private static void require(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
    }

    private static void capture(List<String> output, Component component) {
        MinecraftText.encode(component); // Native LP output must be accepted by the actual 1.7.10 codec.
        output.add(PlainTextComponentSerializer.plainText().serialize(component));
    }

    private static final class Probe implements Plugin {
        PluginContext context;
        @Override public void onLoad(PluginContext value) { context = value; }
    }

    private static final class TestPlayers implements Players {
        private final List<String> output;
        volatile PlayerView current;
        TestPlayers(List<String> output) { this.output = output; }
        @Override public Optional<PlayerView> find(PlayerIdentity identity) {
            PlayerView player = current;
            return player != null && player.identity().equals(identity) ? Optional.of(player) : Optional.empty();
        }
        @Override public List<PlayerView> online() { PlayerView player = current; return player == null ? List.of() : List.of(player); }
        @Override public CompletionStage<TransferResult> transfer(PlayerIdentity identity, String backendName) {
            return CompletableFuture.completedFuture(TransferResult.failed("acceptance does not transfer network sessions"));
        }
        @Override public CompletionStage<MessageResult> sendMessage(PlayerIdentity identity, Component message) {
            capture(output, message);
            return CompletableFuture.completedFuture(find(identity).isPresent() ? MessageResult.SENT : MessageResult.NOT_CONNECTED);
        }
        @Override public CompletionStage<DisconnectResult> disconnect(PlayerIdentity identity, Component reason) {
            return CompletableFuture.completedFuture(DisconnectResult.NOT_CONNECTED);
        }
    }
}

import dev.moonbridge.api.*;
import dev.moonbridge.api.permission.PermissionResult;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.core.plugin.PluginHost;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Exercises the packaged LuckPerms plugin against external SQL and/or Redis services. */
public final class PermissionNetworkAcceptance {
    private static final String PROVIDER = "dev.moonbridge.luckperms.LuckPermsMoonBridgePlugin";
    private static final long TIMEOUT_SECONDS = 30;
    private static final BufferedReader CONTROL = new BufferedReader(new InputStreamReader(System.in));

    public static void main(String[] args) throws Exception {
        var backgroundFailures = new CopyOnWriteArrayList<String>();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> backgroundFailures.add(
                thread.getName() + ": " + failure.getClass().getName()));
        if (args.length != 4 && args.length != 5) {
            throw new IllegalArgumentException("Usage: PermissionNetworkAcceptance <distribution> <config-path> <evidence-parent> <sql|redis> [outage]");
        }
        Path distribution = Path.of(args[0]).toAbsolutePath();
        String mode = args[3].toLowerCase(Locale.ROOT);
        Path evidenceRoot = Files.createDirectories(Path.of(args[2]).toAbsolutePath());
        boolean outage = args.length == 5 && args[4].equalsIgnoreCase("outage");
        require(args.length < 5 || outage, "optional fifth argument must be outage");
        if (!mode.equals("sql") && !mode.equals("redis")) {
            throw new IllegalArgumentException("Mode must be sql or redis");
        }
        String configTemplate = Files.readString(Path.of(args[1]).toAbsolutePath());
        if (mode.equals("sql")) {
            require(configTemplate.contains("storage-method: mysql"), "SQL mode requires MySQL storage");
            require(configTemplate.contains("messaging-service: auto"), "SQL mode must exercise automatic SQL messenger selection");
        } else {
            require(configTemplate.contains("storage-method: mysql"), "Redis mode still requires shared MySQL persistence");
            require(configTemplate.contains("messaging-service: redis"), "Redis mode requires the Redis messenger");
        }

        Path evidence = Files.createTempDirectory(evidenceRoot, "permission-network-" + mode + "-");
        UUID playerId = UUID.randomUUID();
        String user = "lp user " + playerId + " ";
        String uniqueGroup = "acceptance_network_" + playerId.toString().replace("-", "").substring(0, 10);
        Path pluginA = preparePlugin(distribution, evidence.resolve("instance-a"),
                configForServer(configTemplate, "acceptance-a"));
        Path pluginB = preparePlugin(distribution, evidence.resolve("instance-b"),
                configForServer(configTemplate, "acceptance-b"));
        var transcript = new CopyOnWriteArrayList<String>();
        var playersA = new TestPlayers();
        var playersB = new TestPlayers();
        PlayerView a = new PlayerView(new PlayerIdentity(playerId, 1), "NetworkPermProbe", "lobby");
        PlayerView b = new PlayerView(new PlayerIdentity(playerId, 1), "NetworkPermProbe", "lobby");
        playersA.current = a;
        playersB.current = b;

        try (Running first = start(pluginA, "acceptance-proxy-a", playersA, transcript);
             Running second = start(pluginB, "acceptance-proxy-b", playersB, transcript)) {
            first.host.permissionService().prepare(a).toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            second.host.permissionService().prepare(b).toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Both users are loaded before writes so a successful result requires the configured messenger
            // to invalidate the remote instance's already-populated user/cache.
            expect(first, a, "acceptance.network.grant", PermissionResult.UNDEFINED);
            expect(second, b, "acceptance.network.grant", PermissionResult.UNDEFINED);
            command(first, "lp user " + playerId + " permission set acceptance.network.grant true");
            expect(second, b, "acceptance.network.grant", PermissionResult.ALLOW);
            command(first, "lp user " + playerId + " permission set acceptance.network.grant false");
            expect(second, b, "acceptance.network.grant", PermissionResult.DENY);
            command(first, "lp user " + playerId + " permission unset acceptance.network.grant");
            expect(second, b, "acceptance.network.grant", PermissionResult.UNDEFINED);

            command(first, "lp creategroup " + uniqueGroup);
            command(first, "lp group " + uniqueGroup + " permission set acceptance.network.inherited true");
            command(first, user + "parent add " + uniqueGroup);
            expect(first, a, "acceptance.network.inherited", PermissionResult.ALLOW);
            expect(second, b, "acceptance.network.inherited", PermissionResult.ALLOW);
            command(first, "lp user " + playerId + " permission set acceptance.network.inherited false");
            expect(second, b, "acceptance.network.inherited", PermissionResult.DENY);
            command(first, user + "permission unset acceptance.network.inherited");
            expect(second, b, "acceptance.network.inherited", PermissionResult.ALLOW);

            command(first, user + "permission set acceptance.network.context true server=acceptance-a");
            expect(first, a, "acceptance.network.context", PermissionResult.ALLOW);
            expect(second, b, "acceptance.network.context", PermissionResult.UNDEFINED);

            command(first, user + "permission settemp acceptance.network.temporary true 2s");
            expect(first, a, "acceptance.network.temporary", PermissionResult.ALLOW);
            await(() -> first.probe.context.permissions().check(a.identity(), "acceptance.network.temporary")
                    == PermissionResult.UNDEFINED, "temporary permission expiry");

            if (outage && mode.equals("sql")) {
                runMysqlOutage(first, second, a, b, playersA, playerId);
            } else if (outage) {
                runRedisOutage(first, second, a, b, playerId, user);
            }

            // Restart the second isolated plugin/classloader and prove shared SQL data survives a fresh load.
            second.host.permissionService().release(b.identity());
            playersB.current = null;
        }

        try (Running restarted = start(pluginB, "acceptance-proxy-b", playersB, transcript)) {
            PlayerView returning = new PlayerView(new PlayerIdentity(playerId, 2), "NetworkPermProbe", "lobby");
            playersB.current = returning;
            restarted.host.permissionService().prepare(returning).toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            expect(restarted, returning, "acceptance.network.inherited", PermissionResult.ALLOW);
            expect(restarted, returning, "acceptance.network.context", PermissionResult.UNDEFINED);
            if (outage && mode.equals("redis")) {
                expect(restarted, returning, "acceptance.network.redis-during-outage", PermissionResult.ALLOW);
                expect(restarted, returning, "acceptance.network.redis-after-recovery", PermissionResult.ALLOW);
            }
            restarted.host.permissionService().release(returning.identity());
            playersB.current = null;
        }

        Thread.sleep(500);
        require(backgroundFailures.isEmpty(), "uncaught background failures: " + backgroundFailures);
        // Capture command output only; never log config contents, connection URLs, usernames, or passwords.
        Files.write(evidence.resolve("command-output.txt"), transcript);
        Files.writeString(evidence.resolve("result.txt"), "PERMISSION_NETWORK_ACCEPTANCE_PASS mode=" + mode
                + " outage=" + outage + " player=" + playerId + " group=" + uniqueGroup + "\n");
        System.out.println("PERMISSION_NETWORK_ACCEPTANCE_PASS mode=" + mode + " evidence=" + evidence);
    }

    private static Path preparePlugin(Path distribution, Path instance, String config) throws Exception {
        Path plugins = Files.createDirectories(instance.resolve("plugins"));
        try (var files = Files.list(distribution.resolve("plugins"))) {
            Path jar = files.filter(file -> file.getFileName().toString().startsWith("luckperms-moonbridge-")
                    && file.toString().endsWith(".jar") && !file.toString().endsWith("-engine.jar"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Packaged LuckPerms plugin JAR not found"));
            Files.copy(jar, plugins.resolve(jar.getFileName()));
        }
        Path data = Files.createDirectories(plugins.resolve("data").resolve(PROVIDER));
        Files.writeString(data.resolve("config.yml"), config);
        return plugins;
    }

    private static String configForServer(String template, String server) {
        if (template.contains("@SERVER@")) return template.replace("@SERVER@", server);
        String configured = template.replaceFirst("(?m)^server:.*$", "server: " + server);
        require(!configured.equals(template), "config must contain a top-level server setting or @SERVER@");
        return configured;
    }

    private static void runMysqlOutage(Running first, Running second, PlayerView a, PlayerView b,
                                       TestPlayers playersA, UUID playerId) throws Exception {
        command(first, "lp user " + playerId + " permission set acceptance.network.mysql-cache true");
        expect(first, a, "acceptance.network.mysql-cache", PermissionResult.ALLOW);
        expect(second, b, "acceptance.network.mysql-cache", PermissionResult.ALLOW);

        handshake("REQUEST_STOP_MYSQL", "MYSQL_STOPPED");
        expect(first, a, "acceptance.network.mysql-cache", PermissionResult.ALLOW);
        expect(second, b, "acceptance.network.mysql-cache", PermissionResult.ALLOW);

        UUID freshId = UUID.randomUUID();
        PlayerView fresh = new PlayerView(new PlayerIdentity(freshId, 40), "FreshMysqlProbe", "lobby");
        playersA.current = fresh;
        try {
            first.host.permissionService().prepare(fresh).toCompletableFuture().get(20, TimeUnit.SECONDS);
            throw new AssertionError("prepare unexpectedly succeeded while MySQL was stopped");
        } catch (ExecutionException | CompletionException | TimeoutException expected) {
            first.transcript.add("MYSQL_OUTAGE_FRESH_PREPARE_FAILED " + expected.getClass().getSimpleName());
        }
        require(first.probe.context.permissions().check(fresh.identity(), "acceptance.network.mysql-cache")
                        == PermissionResult.UNAVAILABLE,
                "failed new-user preparation must remain UNAVAILABLE during MySQL outage");

        handshake("REQUEST_START_MYSQL", "MYSQL_STARTED");
        playersA.current = a;
        PlayerView recovered = null;
        Throwable lastFailure = null;
        for (int attempt = 0; attempt < 6 && recovered == null; attempt++) {
            PlayerView candidate = new PlayerView(new PlayerIdentity(freshId, 50 + attempt), "FreshMysqlProbe", "lobby");
            try {
                first.host.permissionService().prepare(candidate).toCompletableFuture().get(15, TimeUnit.SECONDS);
                recovered = candidate;
                first.transcript.add("MYSQL_OUTAGE_RECOVERED_PREPARE attempt=" + (attempt + 1));
            } catch (ExecutionException | CompletionException | TimeoutException failure) {
                lastFailure = failure;
                first.transcript.add("MYSQL_OUTAGE_RETRY_FAILED attempt=" + (attempt + 1));
                Thread.sleep(1000);
            }
        }
        if (recovered == null) throw new AssertionError("prepare did not recover after MySQL restart", lastFailure);
        expect(first, recovered, "acceptance.network.no-such-recovery-node", PermissionResult.UNDEFINED);
        command(first, "lp user " + freshId + " permission set acceptance.network.mysql-recovered true");
        expect(first, recovered, "acceptance.network.mysql-recovered", PermissionResult.ALLOW);
        first.host.permissionService().release(recovered.identity());
    }

    private static void runRedisOutage(Running first, Running second, PlayerView a, PlayerView b,
                                       UUID playerId, String user) throws Exception {
        handshake("REQUEST_STOP_REDIS", "REDIS_STOPPED");
        command(first, user + "permission set acceptance.network.redis-during-outage true");
        expect(first, a, "acceptance.network.redis-during-outage", PermissionResult.ALLOW);
        expect(second, b, "acceptance.network.redis-during-outage", PermissionResult.UNDEFINED);

        handshake("REQUEST_START_REDIS", "REDIS_STARTED");
        command(first, user + "permission set acceptance.network.redis-after-recovery true");
        expect(second, b, "acceptance.network.redis-after-recovery", PermissionResult.ALLOW);
        first.transcript.add("REDIS_OUTAGE_DATABASE_WRITE_PLAYER " + playerId);
    }

    private static void handshake(String request, String acknowledgement) throws Exception {
        System.out.println(request);
        System.out.flush();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            if (!CONTROL.ready()) {
                Thread.sleep(25);
                continue;
            }
            String line = CONTROL.readLine();
            if (line == null) throw new IllegalStateException("control stdin closed while awaiting " + acknowledgement);
            if (line.equals(acknowledgement)) return;
            throw new IllegalStateException("unexpected control acknowledgement");
        }
        throw new TimeoutException("timed out waiting for " + acknowledgement);
    }

    private static Running start(Path plugins, String proxyId, Players players,
                                 List<String> transcript) throws Exception {
        PluginHost host = new PluginHost(new InMemoryBackendCatalog(), players, Duration.ofSeconds(5));
        Probe probe = new Probe();
        try {
            host.load(List.of(probe));
            host.loadPlugins(plugins, Map.of(PROVIDER, Map.of("proxy-id", proxyId)));
            host.enable();
            return new Running(host, probe, transcript);
        } catch (Throwable failure) {
            host.close();
            throw failure;
        }
    }

    private static void command(Running running, String value) throws Exception {
        running.transcript.add("COMMAND " + value);
        require(running.probe.context.commands().execute(
                CommandSource.console(component -> running.transcript.add(
                        PlainTextComponentSerializer.plainText().serialize(component))), value)
                .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "registered command: " + value);
    }

    private static void expect(Running running, PlayerView player, String node,
                               PermissionResult expected) throws Exception {
        await(() -> running.probe.context.permissions().check(player.identity(), node) == expected,
                node + " = " + expected);
    }

    private static void await(java.util.function.BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for " + description);
            Thread.sleep(50);
        }
    }

    private static void require(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
    }

    private record Running(PluginHost host, Probe probe, List<String> transcript) implements AutoCloseable {
        @Override public void close() { host.close(); }
    }

    private static final class Probe implements Plugin {
        PluginContext context;
        @Override public void onLoad(PluginContext value) { context = value; }
    }

    private static final class TestPlayers implements Players {
        volatile PlayerView current;
        @Override public Optional<PlayerView> find(PlayerIdentity identity) {
            PlayerView player = current;
            return player != null && player.identity().equals(identity) ? Optional.of(player) : Optional.empty();
        }
        @Override public List<PlayerView> online() {
            PlayerView player = current;
            return player == null ? List.of() : List.of(player);
        }
        @Override public CompletionStage<TransferResult> transfer(PlayerIdentity identity, String backendName) {
            return CompletableFuture.completedFuture(TransferResult.failed("acceptance probe does not transfer"));
        }
        @Override public CompletionStage<MessageResult> sendMessage(PlayerIdentity identity, Component message) {
            return CompletableFuture.completedFuture(find(identity).isPresent()
                    ? MessageResult.SENT : MessageResult.NOT_CONNECTED);
        }
        @Override public CompletionStage<DisconnectResult> disconnect(PlayerIdentity identity, Component reason) {
            return CompletableFuture.completedFuture(DisconnectResult.NOT_CONNECTED);
        }
    }
}

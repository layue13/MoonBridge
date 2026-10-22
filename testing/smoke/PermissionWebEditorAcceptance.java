import dev.moonbridge.api.CommandSource;
import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.MessageResult;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.api.Players;
import dev.moonbridge.api.TransferResult;
import dev.moonbridge.api.permission.PermissionResult;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.core.plugin.PluginHost;
import dev.moonbridge.core.protocol.MinecraftText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interactive native-LuckPerms Web Editor acceptance against an isolated assembled distribution. */
public final class PermissionWebEditorAcceptance {
    private static final String PROVIDER = "dev.moonbridge.luckperms.LuckPermsMoonBridgePlugin";
    private static final UUID PLAYER_ID = UUID.fromString("1f20f54a-62d2-4ef5-8e49-2a0d0b6c9174");
    private static final String USERNAME = "WebEditorProbe";
    private static final String NODE = "acceptance.webeditor";
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"']+");
    private static final long DEADLINE_NANOS = TimeUnit.MINUTES.toNanos(10);

    private PermissionWebEditorAcceptance() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: PermissionWebEditorAcceptance <distribution> <evidence parent directory>");
        }
        Path distribution = Path.of(args[0]).toAbsolutePath();
        Path evidenceRoot = Files.createDirectories(Path.of(args[1]).toAbsolutePath());
        Path evidence = Files.createTempDirectory(evidenceRoot, "web-editor-");
        Path plugins = Files.createDirectories(evidence.resolve("plugins"));
        Path data = Files.createDirectories(plugins.resolve("data").resolve(PROVIDER));
        try (var files = Files.list(distribution.resolve("plugins"))) {
            Path jar = files.filter(file -> file.getFileName().toString().startsWith("luckperms-moonbridge-")
                    && file.toString().endsWith(".jar") && !file.toString().endsWith("-engine.jar"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Native LuckPerms plugin JAR not found in distribution"));
            Files.copy(jar, plugins.resolve(jar.getFileName()));
        }
        Files.writeString(data.resolve("config.yml"),
                "server: acceptance-proxy\nstorage-method: h2\nmessaging-service: none\n");

        var transcript = new CopyOnWriteArrayList<String>();
        var backgroundFailures = new CopyOnWriteArrayList<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            backgroundFailures.add(failure);
            failure.printStackTrace();
        });
        var stdin = new ArrayBlockingQueue<String>(8);
        var inputEnded = new AtomicBoolean();
        Thread.ofPlatform().daemon().name("web-editor-acceptance-stdin").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!stdin.offer(line)) {
                        stdin.poll();
                        stdin.offer(line);
                    }
                }
            } catch (Throwable failure) {
                backgroundFailures.add(failure);
            } finally {
                inputEnded.set(true);
            }
        });

        TestPlayers players = new TestPlayers(transcript);
        PlayerView player = new PlayerView(new PlayerIdentity(PLAYER_ID, 1), USERNAME, "lobby");
        long deadline = System.nanoTime() + DEADLINE_NANOS;
        boolean applied = false;
        String applyMode = "";
        writeEvidence(evidence, transcript, "WEB_EDITOR_ACCEPTANCE_WAITING\n");

        try (PluginHost host = new PluginHost(new InMemoryBackendCatalog(), players, Duration.ofSeconds(5))) {
            Probe probe = start(host, plugins);
            players.current = player;
            host.permissionService().prepare(player).toCompletableFuture().get(30, TimeUnit.SECONDS);
            check(probe, player, PermissionResult.UNDEFINED, "before opening editor");
            execute(probe, transcript, "lp user " + PLAYER_ID + " editor");

            String url = awaitUrl(transcript, deadline, backgroundFailures);
            Files.writeString(evidence.resolve("web-editor.txt"),
                    "player-uuid=" + PLAYER_ID + "\nusername=" + USERNAME + "\npermission=" + NODE + "\nurl=" + url + "\n");
            System.out.println("WEB_EDITOR_URL=" + url);
            System.out.println("WEB_EDITOR_PLAYER=" + USERNAME + " (" + PLAYER_ID + ")");
            System.out.println("WEB_EDITOR_PERMISSION=" + NODE);
            System.out.println("Apply in the browser, then enter `lp applyedits <code>` here if WebEditor does not auto-apply.");
            System.out.println("Enter `quit` to stop. Deadline: 10 minutes.");
            boolean stdinClosedNotified = false;

            while (System.nanoTime() < deadline) {
                checkBackground(backgroundFailures);
                if (probe.context.permissions().check(player.identity(), NODE) == PermissionResult.ALLOW) {
                    applied = true;
                    applyMode = "websocket-auto";
                    break;
                }
                String line = stdin.poll(250, TimeUnit.MILLISECONDS);
                if (line == null) {
                    if (inputEnded.get() && !stdinClosedNotified) {
                        System.out.println("stdin closed; continuing to watch for WebEditor websocket updates until timeout.");
                        stdinClosedNotified = true;
                    }
                    continue;
                }
                String command = line.trim();
                if (command.equalsIgnoreCase("quit")) {
                    System.out.println("WEB_EDITOR_ACCEPTANCE_STOPPED");
                    return;
                }
                if (command.isEmpty()) continue;
                if (!command.matches("(?i)^/?lp\\s+applyedits\\s+\\S+\\s*$")) {
                    System.out.println("Input ignored; expected `lp applyedits <code>` or `quit`.");
                    continue;
                }
                execute(probe, transcript, command.startsWith("/") ? command.substring(1) : command);
                // A successful command can either apply through the editor socket or explicitly through applyedits.
                long applyDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(20));
                while (System.nanoTime() < applyDeadline
                        && probe.context.permissions().check(player.identity(), NODE) != PermissionResult.ALLOW) {
                    checkBackground(backgroundFailures);
                    Thread.sleep(100);
                }
                if (probe.context.permissions().check(player.identity(), NODE) == PermissionResult.ALLOW) {
                    applied = true;
                    applyMode = "applyedits-command";
                    break;
                }
                System.out.println("The permission is not ALLOW yet; you can enter another applyedits code or wait for WebEditor.");
            }
            if (!applied) throw new IllegalStateException("Web Editor did not grant " + NODE + " before the 10-minute deadline");
            check(probe, player, PermissionResult.ALLOW, "after editor apply");
            host.permissionService().release(player.identity());
            players.current = null;
        }

        // A fresh PluginHost classloader and fresh H2 storage connection must read the saved grant.
        try (PluginHost restarted = new PluginHost(new InMemoryBackendCatalog(), players, Duration.ofSeconds(5))) {
            Probe probe = start(restarted, plugins);
            PlayerView returning = new PlayerView(new PlayerIdentity(PLAYER_ID, 2), USERNAME, "lobby");
            players.current = returning;
            restarted.permissionService().prepare(returning).toCompletableFuture().get(30, TimeUnit.SECONDS);
            check(probe, returning, PermissionResult.ALLOW, "after host restart");
            restarted.permissionService().release(returning.identity());
            players.current = null;
        }

        Thread.sleep(300);
        checkBackground(backgroundFailures);
        writeEvidence(evidence, transcript, "WEB_EDITOR_ACCEPTANCE_PASS\nmode=" + applyMode + "\n");
        System.out.println("WEB_EDITOR_ACCEPTANCE_PASS mode=" + applyMode + " evidence=" + evidence);
    }

    private static Probe start(PluginHost host, Path pluginDirectory) throws Exception {
        Probe probe = new Probe();
        host.load(List.of(probe));
        host.loadPlugins(pluginDirectory, Map.of(PROVIDER, Map.of("proxy-id", "acceptance-proxy")));
        host.enable();
        return probe;
    }

    private static void execute(Probe probe, List<String> transcript, String command) throws Exception {
        boolean handled = probe.context.commands().execute(
                CommandSource.console(message -> capture(transcript, message)), command)
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        if (!handled) throw new IllegalStateException("LuckPerms did not consume command root: " + root(command));
    }

    private static String root(String command) {
        String trimmed = command.startsWith("/") ? command.substring(1) : command;
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    private static String awaitUrl(List<String> transcript, long deadline, List<Throwable> backgroundFailures) throws Exception {
        while (System.nanoTime() < deadline) {
            checkBackground(backgroundFailures);
            for (String line : transcript) {
                Matcher matcher = URL.matcher(line);
                if (matcher.find()) return matcher.group().replaceAll("[),.;]+$", "");
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("LuckPerms editor command did not emit a URL before the deadline");
    }

    private static void check(Probe probe, PlayerView player, PermissionResult expected, String phase) {
        PermissionResult actual = probe.context.permissions().check(player.identity(), NODE);
        if (actual != expected) throw new AssertionError(phase + ": expected " + expected + " for " + NODE + ", got " + actual);
    }

    private static void checkBackground(List<Throwable> failures) {
        if (!failures.isEmpty()) throw new AssertionError("background plugin failure: " + failures.getFirst(), failures.getFirst());
    }

    private static void writeEvidence(Path evidence, List<String> transcript, String result) throws Exception {
        Files.write(evidence.resolve("console-output.txt"), transcript);
        Files.writeString(evidence.resolve("result.txt"), result);
    }

    private static void capture(List<String> transcript, Component component) {
        MinecraftText.encode(component);
        String line = PlainTextComponentSerializer.plainText().serialize(component);
        transcript.add(line);
        if (URL.matcher(line).find()) System.out.println("WEB_EDITOR_OUTPUT=" + line);
    }

    private static final class Probe implements Plugin {
        private PluginContext context;
        @Override public void onLoad(PluginContext loaded) { context = loaded; }
    }

    private static final class TestPlayers implements Players {
        private final List<String> transcript;
        private volatile PlayerView current;
        private TestPlayers(List<String> transcript) { this.transcript = transcript; }
        @Override public Optional<PlayerView> find(PlayerIdentity identity) {
            PlayerView player = current;
            return player != null && player.identity().equals(identity) ? Optional.of(player) : Optional.empty();
        }
        @Override public List<PlayerView> online() {
            PlayerView player = current;
            return player == null ? List.of() : List.of(player);
        }
        @Override public CompletionStage<TransferResult> transfer(PlayerIdentity identity, String backendName) {
            return CompletableFuture.completedFuture(TransferResult.failed("Web Editor acceptance does not transfer players"));
        }
        @Override public CompletionStage<MessageResult> sendMessage(PlayerIdentity identity, Component message) {
            capture(transcript, message);
            return CompletableFuture.completedFuture(find(identity).isPresent() ? MessageResult.SENT : MessageResult.NOT_CONNECTED);
        }
        @Override public CompletionStage<DisconnectResult> disconnect(PlayerIdentity identity, Component reason) {
            return CompletableFuture.completedFuture(DisconnectResult.NOT_CONNECTED);
        }
    }
}

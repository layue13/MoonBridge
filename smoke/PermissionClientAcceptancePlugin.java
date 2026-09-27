package dev.moonbridge.smoke;

import dev.moonbridge.api.CommandSource;
import dev.moonbridge.api.MessageResult;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.api.event.EventSubscription;
import dev.moonbridge.api.event.PlayerDisconnectedEvent;
import dev.moonbridge.api.permission.PermissionResult;
import dev.moonbridge.core.protocol.MinecraftText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Server-driven permission checks against an actual Forge client session. */
public final class PermissionClientAcceptancePlugin implements Plugin {
    private static final String PLAYER_NAME = "PrismSmoke";
    private static final String BACKEND_NAME = "uranium";
    private static final String COMMAND_PERMISSION = "acceptance.client.execute";
    private static final String COMMAND_ROOT = "permclient";
    private static final String USER = "lp user ";
    private static final String DENIED_TEXT = "You do not have permission to use this command.";

    private final AtomicInteger handlerExecutions = new AtomicInteger();
    private final AtomicReference<PlayerIdentity> targetIdentity = new AtomicReference<>();
    private final AtomicReference<PlayerIdentity> disconnectedIdentity = new AtomicReference<>();
    private volatile PluginContext context;
    private volatile Path evidenceDirectory;
    private volatile boolean stopping;
    private volatile Thread worker;
    private volatile EventSubscription disconnectSubscription;

    @Override public void onLoad(PluginContext loadedContext) {
        context = loadedContext;
        String configured = loadedContext.settings().get("evidenceDir");
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("evidenceDir setting is required");
        }
        evidenceDirectory = Path.of(configured).toAbsolutePath().normalize();
    }

    @Override public void onEnable() {
        context.commands().register(COMMAND_ROOT, COMMAND_PERMISSION, invocation -> {
            PlayerIdentity current = targetIdentity.get();
            if (current == null || !current.equals(invocation.player().identity())) {
                throw new IllegalStateException("command invocation did not use the connected acceptance player");
            }
            handlerExecutions.incrementAndGet();
            if (!"run".equals(invocation.arguments())) {
                invocation.reply("usage: /permclient run");
                return;
            }
            invocation.reply(richCommandReply());
        });
        disconnectSubscription = context.events().subscribe(PlayerDisconnectedEvent.class, event -> {
            PlayerIdentity expected = targetIdentity.get();
            if (expected != null && expected.equals(event.player().identity())) {
                disconnectedIdentity.set(expected);
                record("CLIENT_DISCONNECTED connectionId=" + expected.connectionId());
            }
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        worker = Thread.ofVirtual().name("permission-client-acceptance").start(this::runAcceptance);
        record("PLUGIN_READY backend=" + BACKEND_NAME + " player=" + PLAYER_NAME);
    }

    @Override public void onDisable() {
        stopping = true;
        Thread active = worker;
        if (active != null) active.interrupt();
        EventSubscription subscription = disconnectSubscription;
        if (subscription != null) subscription.close();
    }

    private void runAcceptance() {
        try {
            PlayerView player = waitForPlayer(360);
            targetIdentity.set(player.identity());
            record("PLAYER_CONNECTED name=" + player.username()
                    + " uuid=" + player.identity().playerId()
                    + " connectionId=" + player.identity().connectionId()
                    + " backend=" + player.currentServer().orElse("<none>"));

            awaitResult(player.identity(), COMMAND_PERMISSION, PermissionResult.UNDEFINED, 30);
            runProxyCommandAsPlayer(player, "/" + COMMAND_ROOT + " run", "undefined permission denial", 0, false);
            require(handlerExecutions.get() == 0, "undefined permission must not call the command handler");

            consoleCommand(USER + player.identity().playerId() + " permission set "
                    + COMMAND_PERMISSION + " true");
            awaitResult(player.identity(), COMMAND_PERMISSION, PermissionResult.ALLOW, 30);
            runProxyCommandAsPlayer(player, "/" + COMMAND_ROOT + " run", "granted command", 1, true);
            require(handlerExecutions.get() == 1, "grant must run the business handler exactly once");

            consoleCommand(USER + player.identity().playerId() + " permission set "
                    + COMMAND_PERMISSION + " false");
            awaitResult(player.identity(), COMMAND_PERMISSION, PermissionResult.DENY, 30);
            runProxyCommandAsPlayer(player, "/" + COMMAND_ROOT + " run", "explicit deny", 1, false);
            require(handlerExecutions.get() == 1, "explicit deny must leave the business handler count unchanged");

            consoleCommand(USER + player.identity().playerId() + " permission set luckperms.* true");
            awaitResult(player.identity(), "luckperms.user.permission.set", PermissionResult.ALLOW, 30);
            runLuckPermsPlayerCommand(player, "/lp info", "LuckPerms info");
            // LuckPerms limits player commands to one per 500 ms; keep the
            // smoke from testing its rate limiter instead of permission output.
            Thread.sleep(600);
            runLuckPermsPlayerCommand(player, USER + player.identity().playerId() + " permission info",
                    "LuckPerms permission info");

            Component rich = Component.text("PERMISSION_CLIENT_RICH_TEXT_PASS", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.suggestCommand("/help"))
                    .hoverEvent(HoverEvent.showText(Component.text("PERMISSION_CLIENT_HOVER_PASS")));
            String encoded = MinecraftText.encode(rich);
            require(encoded.contains("clickEvent") && encoded.contains("hoverEvent") && encoded.contains("value"),
                    "proxy MinecraftText encoder must preserve supported click and hover fields");
            MessageResult sent = context.players().sendMessage(player.identity(), rich)
                    .toCompletableFuture().get(15, TimeUnit.SECONDS);
            require(sent == MessageResult.SENT, "rich-text marker send returned " + sent);
            record("RICH_TEXT_SENT status=" + sent + " proxyEncoder=PASS click=true hover=true jsonChars="
                    + encoded.length());

            for (int second = 0; second < 10; second++) {
                ensureSameSession(player);
                Thread.sleep(1_000);
            }
            ensureSameSession(player);
            require(disconnectedIdentity.get() == null, "target client disconnected during the hold");
            record("PERMISSION_CLIENT_ACCEPTANCE_PASS handlerExecutions=1 heldSeconds=10"
                    + " clientCommandOrigin=proxy-api playerKeyboardInput=false");
        } catch (InterruptedException interrupted) {
            if (!stopping) {
                Thread.currentThread().interrupt();
                fail("acceptance interrupted unexpectedly", interrupted);
            }
        } catch (Throwable failure) {
            fail("acceptance failed", failure);
        }
    }

    private PlayerView waitForPlayer(int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline && !stopping) {
            Optional<PlayerView> match = context.players().online().stream()
                    .filter(player -> PLAYER_NAME.equals(player.username()))
                    .filter(player -> BACKEND_NAME.equals(player.currentServer().orElse(null)))
                    .findFirst();
            if (match.isPresent()) return match.get();
            Thread.sleep(250);
        }
        throw new IllegalStateException("no online " + PLAYER_NAME + " session on " + BACKEND_NAME
                + " within " + timeoutSeconds + " seconds");
    }

    private void runProxyCommandAsPlayer(PlayerView player, String command, String phase,
                                         int expectedHandlerCount, boolean expectedAllowed) throws Exception {
        List<Component> replies = new CopyOnWriteArrayList<>();
        boolean consumed = context.commands().execute(CommandSource.player(player, replies::add), command)
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        require(consumed, phase + " command was not consumed by the proxy");
        if (!expectedAllowed) {
            require(replies.stream().map(PlainTextComponentSerializer.plainText()::serialize)
                    .anyMatch(DENIED_TEXT::equals), phase + " did not return the expected permission denial");
        } else {
            require(replies.stream().anyMatch(component ->
                            PlainTextComponentSerializer.plainText().serialize(component)
                                    .contains("PERMISSION_CLIENT_COMMAND_PASS")),
                    phase + " handler did not generate its marker reply");
        }
        if (!replies.isEmpty()) sendComponents(player.identity(), replies, phase + " command reply");
        record("COMMAND_PHASE=" + phase.replace(' ', '_') + " consumed=true handlerExecutions="
                + handlerExecutions.get() + " replyComponents=" + replies.size());
    }

    private void runLuckPermsPlayerCommand(PlayerView player, String command, String phase) throws Exception {
        List<Component> replies = new CopyOnWriteArrayList<>();
        boolean consumed = context.commands().execute(CommandSource.player(player, replies::add), command)
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        require(consumed, phase + " command was not consumed by the proxy");
        require(!replies.isEmpty(), phase + " returned no player-facing LuckPerms components");
        int styled = 0;
        for (Component component : replies) {
            String plain = PlainTextComponentSerializer.plainText().serialize(component);
            require(!plain.contains("luckperms.command."),
                    phase + " contains an untranslated LuckPerms key: " + plain);
            String json = MinecraftText.encode(component);
            if (isStyled(component)) styled++;
            require(!json.isBlank(), phase + " component encoded to empty JSON");
        }
        require(styled > 0, phase + " returned no styled Adventure component");
        sendComponents(player.identity(), replies, phase);
        record("LUCKPERMS_PLAYER_OUTPUT phase=" + phase.replace(' ', '_') + " components=" + replies.size()
                + " styled=" + styled + " proxyEncoder=PASS deliveredToClient=true");
    }

    private void consoleCommand(String command) throws Exception {
        List<Component> output = new CopyOnWriteArrayList<>();
        boolean consumed = context.commands().execute(CommandSource.console(output::add), command)
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        require(consumed, "LuckPerms console command was not consumed: " + command);
        record("LUCKPERMS_CONSOLE_COMMAND consumed=true outputComponents=" + output.size());
    }

    private void sendComponents(PlayerIdentity identity, List<Component> components, String description)
            throws Exception {
        int sent = 0;
        for (Component component : components) {
            // Exercise the exact proxy serializer used by the actual Players.sendMessage packet path.
            MinecraftText.encode(component);
            MessageResult result = context.players().sendMessage(identity, component)
                    .toCompletableFuture().get(15, TimeUnit.SECONDS);
            require(result == MessageResult.SENT, description + " send returned " + result);
            sent++;
        }
        record("CLIENT_MESSAGE_DELIVERY phase=" + description.replace(' ', '_') + " sent=" + sent);
    }

    private void awaitResult(PlayerIdentity identity, String node, PermissionResult expected,
                             int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        PermissionResult observed = PermissionResult.UNAVAILABLE;
        while (System.nanoTime() < deadline && !stopping) {
            observed = context.permissions().check(identity, node);
            if (observed == expected) {
                record("PERMISSION_CHECK node=" + node + " result=" + observed);
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("permission " + node + " expected " + expected + " but observed " + observed);
    }

    private void ensureSameSession(PlayerView expected) {
        PlayerView current = context.players().find(expected.identity())
                .orElseThrow(() -> new IllegalStateException("target connection is no longer online"));
        require(current.username().equals(expected.username()), "target username changed during the test");
        require(current.currentServer().orElse("").equals(BACKEND_NAME), "target backend changed during the test");
    }

    private static Component richCommandReply() {
        return Component.text("PERMISSION_CLIENT_COMMAND_PASS", NamedTextColor.GOLD)
                .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                .clickEvent(ClickEvent.suggestCommand("/help"))
                .hoverEvent(HoverEvent.showText(Component.text("permission-gated command reply")));
    }

    private static boolean isStyled(Component component) {
        if (component.style().color() != null || component.style().clickEvent() != null
                || component.style().hoverEvent() != null) return true;
        return component.children().stream().anyMatch(PermissionClientAcceptancePlugin::isStyled);
    }

    private void record(String line) {
        try {
            Files.createDirectories(evidenceDirectory);
            Files.writeString(evidenceDirectory.resolve("result.log"), line + System.lineSeparator(),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException failure) {
            context.logger().error("Could not write permission client acceptance evidence", failure);
        }
    }

    private void fail(String description, Throwable failure) {
        context.logger().error("Permission client acceptance " + description, failure);
        record("PERMISSION_CLIENT_ACCEPTANCE_FAIL phase=" + description.replace(' ', '_')
                + " error=" + failure.getClass().getName() + ": " + String.valueOf(failure.getMessage()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

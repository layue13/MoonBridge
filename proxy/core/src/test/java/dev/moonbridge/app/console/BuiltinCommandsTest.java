package dev.moonbridge.app.console;

import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.TransferResult;
import dev.moonbridge.api.TransferStatus;
import dev.moonbridge.core.admin.AdminView;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendOwner;
import dev.moonbridge.core.backend.BackendRegistration;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.core.log.RecentActivity;
import dev.moonbridge.core.session.OnlinePlayer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinCommandsTest {
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final PlayerIdentity TESTER = new PlayerIdentity(UUID.fromString("3f2a0000-0000-0000-0000-0000000000e1"), 7);
    private static final PlayerIdentity STEVE = new PlayerIdentity(UUID.randomUUID(), 8);

    private final List<String> out = new ArrayList<>();
    private final FakeActions actions = new FakeActions();
    private final BuiltinCommands commands = commands();

    @Test
    void listGroupsPlayersByServer() {
        assertTrue(commands.run("list"));
        assertEquals(List.of("2 players online", "  lobby (1): Tester", "  survival (1): Steve"), out);
    }

    @Test
    void serversShowsAddressPlayersAndSource() {
        commands.run("servers");
        assertEquals(List.of(
                "NAME      ADDRESS          PLAYERS  SOURCE",
                "lobby     127.0.0.1:25565  1        static config",
                "survival  10.0.0.5:25565   1        control channel (island-a-1)"), out);
    }

    @Test
    void playerShowsTheConnectionAndIgnoresNameCase() {
        commands.run("player tester");
        assertEquals(List.of("Tester (" + TESTER.playerId() + ") via WebSocket from 203.0.113.7",
                "  on lobby, online 18m"), out);
        out.clear();
        commands.run("player nobody");
        assertEquals(List.of("No online player named nobody."), out);
    }

    @Test
    void statusSummarisesTheProxyAndRecentActivity() {
        commands.run("status");
        assertEquals("MoonBridge 0.1.0 · up 3h 20m · kqueue · TCP /127.0.0.1:25577", out.get(0));
        assertTrue(out.get(1).startsWith("2 players · 2 servers · heap "), out.get(1));
        assertEquals("Last 10 min: 14 joins · 11 leaves · 2 refused · 9 moves, 1 failed · 0 overload rejections",
                out.get(2));
    }

    @Test
    void sendChecksBothNamesAndReportsTheResult() {
        commands.run("send Tester nowhere");
        assertEquals(List.of("No server named nowhere. Type servers to list them."), out);
        assertTrue(actions.sent.isEmpty());
        out.clear();
        commands.run("send Tester survival");
        assertEquals(List.of(TESTER + " -> survival"), actions.sent);
        assertEquals(List.of("Tester is on survival."), out);
    }

    @Test
    void kickUsesTheGivenReasonOrADefault() {
        commands.run("kick Steve spamming chat");
        commands.run("kick Tester");
        assertEquals(List.of(STEVE + ": spamming chat", TESTER + ": Disconnected by an operator."), actions.kicked);
    }

    @Test
    void loglevelAcceptsAreasAndRejectsUnknownLevels() {
        commands.run("loglevel transfer debug");
        assertEquals(List.of("dev.moonbridge.core.session.transfer=DEBUG"), actions.levels);
        out.clear();
        commands.run("loglevel transfer loud");
        assertEquals(List.of("Unknown level loud; use one of TRACE, DEBUG, INFO, WARN, ERROR, OFF."), out);
    }

    @Test
    void stopAndUnknownCommands() {
        assertTrue(commands.run("stop"));
        assertTrue(actions.stopped);
        assertFalse(commands.run("someplugincommand arg"), "unknown words go on to plugin commands");
        assertFalse(commands.run("   "));
    }

    @Test
    void completionOffersCommandsPlayersServersAndLevels() {
        assertEquals(BuiltinCommands.NAMES, commands.complete(List.of(""), 0));
        assertEquals(List.of("Steve", "Tester"), commands.complete(List.of("kick", ""), 1));
        assertEquals(List.of("lobby", "survival"), commands.complete(List.of("send", "Tester", ""), 2));
        assertTrue(commands.complete(List.of("loglevel", "transfer", ""), 2).contains("DEBUG"));
    }

    private BuiltinCommands commands() {
        var catalog = new InMemoryBackendCatalog();
        catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static-config", 0),
                URI.create("tcp://127.0.0.1:25565"), Map.of(), Map.of()));
        catalog.register(new BackendRegistration(new BackendId("survival"),
                new BackendOwner("backend-control:island-a-1", 1), URI.create("tcp://10.0.0.5:25565"), Map.of(), Map.of()));
        List<OnlinePlayer> online = List.of(
                new OnlinePlayer(new PlayerView(TESTER, "Tester", "lobby"),
                        new InetSocketAddress("203.0.113.7", 50000), true, NOW.minus(Duration.ofMinutes(18))),
                new OnlinePlayer(new PlayerView(STEVE, "Steve", "survival"),
                        new InetSocketAddress("198.51.100.2", 50001), false, NOW.minus(Duration.ofMinutes(4))));
        var view = new AdminView(() -> online, catalog, () -> new RecentActivity(14, 11, 2, 9, 1, 0),
                new AdminView.Identity("0.1.0", NOW.minus(Duration.ofMinutes(200)), "kqueue",
                        List.of("TCP /127.0.0.1:25577")), Clock.fixed(NOW, ZoneOffset.UTC));
        return new BuiltinCommands(view, actions, out::add);
    }

    private static final class FakeActions implements AdminActions {
        final List<String> kicked = new ArrayList<>();
        final List<String> sent = new ArrayList<>();
        final List<String> levels = new ArrayList<>();
        boolean stopped;

        @Override public CompletionStage<DisconnectResult> kick(PlayerIdentity player, String reason) {
            kicked.add(player + ": " + reason);
            return CompletableFuture.completedFuture(DisconnectResult.DISCONNECTED);
        }

        @Override public CompletionStage<TransferResult> send(PlayerIdentity player, String server) {
            sent.add(player + " -> " + server);
            return CompletableFuture.completedFuture(TransferResult.of(TransferStatus.NETWORK_READY));
        }

        @Override public String logLevel(String logger) { return "INFO"; }

        @Override public void setLogLevel(String logger, String level) { levels.add(logger + "=" + level); }

        @Override public void stop() { stopped = true; }
    }
}

package dev.moonbridge.core.log;

import dev.moonbridge.testing.LogCapture;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionLogTest {
    private static final UUID PLAYER = UUID.fromString("3f2a0000-0000-0000-0000-0000000000e1");
    private static final InetSocketAddress FROM = new InetSocketAddress("203.0.113.7", 50000);

    @Test
    void playerLinesCarryNameAddressServerAndReason() {
        try (var log = new LogCapture(ConnectionLog.PLAYERS)) {
            var players = new ConnectionLog(true);
            players.connected("Tester", PLAYER, FROM, false, "lobby");
            players.moved("Tester", "lobby", "survival");
            players.moveFailed("Tester", "creative", "backend login timed out", "survival");
            players.left("Tester", "survival", Duration.ofMinutes(72), "client disconnected");
            players.loginFailed("Steve", FROM, "Mojang session verification failed");
            assertEquals(List.of(
                    "INFO Tester (" + PLAYER + ") connected from 203.0.113.7 via TCP -> lobby",
                    "INFO Tester moved lobby -> survival",
                    "WARN Tester could not move to creative: backend login timed out (stays on survival)",
                    "INFO Tester left survival after 1h 12m: client disconnected",
                    "INFO Steve from 203.0.113.7 could not join: Mojang session verification failed"), log.lines());
        }
    }

    @Test
    void addressesCanBeHiddenAndPeerTextIsCleaned() {
        try (var log = new LogCapture(ConnectionLog.PLAYERS)) {
            var players = new ConnectionLog(false);
            players.connected("Evil\nINFO fake", PLAYER, FROM, true, "lobby");
            assertEquals(List.of("INFO Evil?INFO fake (" + PLAYER + ") connected from [hidden] via WebSocket -> lobby"),
                    log.lines());
        }
    }

    @Test
    void serverLinesNameTheOwnerAndUnreachableIsReportedOncePerOutage() {
        try (var log = new LogCapture(ConnectionLog.SERVERS)) {
            URI address = URI.create("tcp://10.0.0.5:25565");
            ConnectionLog.serverRegistered("survival", address, "backend-control:island-a-1");
            ConnectionLog.serverRegistered("lobby", URI.create("tcp://127.0.0.1:25565"), "static-config");
            ConnectionLog.serverRegistered("events", address, "dns-discovery");
            ConnectionLog.serverUnreachable("survival", address);
            ConnectionLog.serverUnreachable("survival", address);
            ConnectionLog.serverReachable("survival");
            ConnectionLog.serverReachable("survival");
            assertEquals(List.of(
                    "INFO Server survival registered by control channel (island-a-1) at 10.0.0.5:25565",
                    "INFO Server lobby registered by static config at 127.0.0.1:25565",
                    "INFO Server events registered by plugin dns-discovery at 10.0.0.5:25565",
                    "WARN Server survival at 10.0.0.5:25565 is unreachable; players are routed to the next entry server",
                    "INFO Server survival is reachable again"), log.lines());
        }
    }

    @Test
    void durationsAreShortAndReadable() {
        assertEquals("45s", ConnectionLog.duration(Duration.ofSeconds(45)));
        assertEquals("18m", ConnectionLog.duration(Duration.ofMinutes(18)));
        assertEquals("1h 12m", ConnectionLog.duration(Duration.ofMinutes(72)));
        assertEquals("2d 3h", ConnectionLog.duration(Duration.ofHours(51)));
    }

    @Test
    void activityCountsWhatThisLogRecorded() {
        try (var ignored = new LogCapture(ConnectionLog.PLAYERS)) {
            var players = new ConnectionLog(true);
            players.connected("Tester", PLAYER, FROM, false, "lobby");
            players.moved("Tester", "lobby", "survival");
            players.moveFailed("Tester", "creative", "timed out", "survival");
            players.left("Tester", "survival", Duration.ofMinutes(1), "client disconnected");
            players.loginFailed("Steve", FROM, "refused");
            var activity = players.activity();
            assertEquals(1, activity.joins());
            assertEquals(1, activity.leaves());
            assertEquals(1, activity.refused());
            assertEquals(1, activity.moves());
            assertEquals(1, activity.failedMoves());
        }
    }
}

package dev.moonbridge.core.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The one place that writes the INFO and WARN lines an operator reads: players joining, leaving, moving and being
 * refused, and servers coming and going. docs/logging.md is the policy these lines follow. Every name and reason is
 * passed through {@link LogText#clean}; player addresses are written only when {@code logging.playerAddresses} allows.
 */
public final class ConnectionLog {
    /** Logger names operators can raise, lower or route separately in logback.xml. */
    public static final String PLAYERS = "moonbridge.players";
    public static final String SERVERS = "moonbridge.servers";

    private static final Logger PLAYER_LOG = LoggerFactory.getLogger(PLAYERS);
    private static final Logger SERVER_LOG = LoggerFactory.getLogger(SERVERS);
    private static final String HIDDEN_ADDRESS = "[hidden]";
    /** Servers whose last initial-routing dial failed, so the warning is written once per outage. */
    private static final Map<String, Boolean> UNREACHABLE = new ConcurrentHashMap<>();
    private static final ThrottledWarning CONNECTION_LIMIT = new ThrottledWarning(PLAYER_LOG,
            "Connection limit reached: refused {} connection(s); raise maxConnections if this is expected load",
            1, TimeUnit.MINUTES);

    /** Refusals of any kind (connection limit, full plugin pools), shared by every ConnectionLog in the process. */
    private static final RecentCounter OVERLOADS = new RecentCounter();

    private final boolean playerAddresses;
    private final RecentCounter joins = new RecentCounter();
    private final RecentCounter leaves = new RecentCounter();
    private final RecentCounter refused = new RecentCounter();
    private final RecentCounter moves = new RecentCounter();
    private final RecentCounter failedMoves = new RecentCounter();

    public ConnectionLog(boolean playerAddresses) { this.playerAddresses = playerAddresses; }

    /** Counts of the events this log wrote in the last ten minutes. */
    public RecentActivity activity() {
        return new RecentActivity(joins.lastTenMinutes(), leaves.lastTenMinutes(), refused.lastTenMinutes(),
                moves.lastTenMinutes(), failedMoves.lastTenMinutes(), OVERLOADS.lastTenMinutes());
    }

    static void overloaded() { OVERLOADS.increment(); }

    public void connected(String name, UUID playerId, InetSocketAddress from, boolean webSocket, String server) {
        joins.increment();
        PLAYER_LOG.info("{} ({}) connected from {} via {} -> {}", LogText.clean(name), playerId, address(from),
                webSocket ? "WebSocket" : "TCP", LogText.clean(server));
    }

    public void moved(String name, String from, String to) {
        moves.increment();
        PLAYER_LOG.info("{} moved {} -> {}", LogText.clean(name), from == null ? "(no server)" : LogText.clean(from),
                LogText.clean(to));
    }

    /** A transfer someone asked for did not happen; the player is still connected. */
    public void moveFailed(String name, String target, String reason, String current) {
        failedMoves.increment();
        PLAYER_LOG.warn("{} could not move to {}: {} (stays on {})", LogText.clean(name), LogText.clean(target),
                LogText.clean(reason), current == null ? "no server" : LogText.clean(current));
    }

    public void left(String name, String server, Duration online, String reason) {
        leaves.increment();
        PLAYER_LOG.info("{} left {} after {}: {}", LogText.clean(name),
                server == null ? "(no server)" : LogText.clean(server), duration(online), LogText.clean(reason));
    }

    /** The player sent Login Start but never reached a server: refused, timed out or gave up. */
    public void loginFailed(String name, InetSocketAddress from, String reason) {
        refused.increment();
        PLAYER_LOG.info("{} from {} could not join: {}", LogText.clean(name), address(from), LogText.clean(reason));
    }

    public static void connectionLimitReached() {
        OVERLOADS.increment();
        CONNECTION_LIMIT.record();
    }

    public static void serverRegistered(String name, URI address, String ownerId) {
        SERVER_LOG.info("Server {} registered by {} at {}", LogText.clean(name), owner(ownerId), hostPort(address));
    }

    public static void serverMoved(String name, URI from, URI to, String ownerId) {
        SERVER_LOG.info("Server {} moved from {} to {} ({})", LogText.clean(name), hostPort(from), hostPort(to),
                owner(ownerId));
    }

    public static void serverUnregistered(String name, String ownerId) {
        UNREACHABLE.remove(name);
        SERVER_LOG.info("Server {} unregistered ({})", LogText.clean(name), owner(ownerId));
    }

    /** Written once when dials to a server start failing; {@link #serverReachable} ends the outage. */
    public static void serverUnreachable(String name, URI address) {
        if (UNREACHABLE.putIfAbsent(name, Boolean.TRUE) == null) {
            SERVER_LOG.warn("Server {} at {} is unreachable; players are routed to the next entry server",
                    LogText.clean(name), hostPort(address));
        }
    }

    public static void serverReachable(String name) {
        if (UNREACHABLE.remove(name) != null) SERVER_LOG.info("Server {} is reachable again", LogText.clean(name));
    }

    String address(InetSocketAddress address) {
        if (!playerAddresses) return HIDDEN_ADDRESS;
        if (address == null) return "<unknown>";
        return address.getAddress() == null ? LogText.clean(address.getHostString())
                : address.getAddress().getHostAddress();
    }

    /** Owner ids are "static-config", "backend-control:&lt;instance&gt;" or a plugin id. */
    public static String owner(String ownerId) {
        if ("static-config".equals(ownerId)) return "static config";
        if (ownerId.startsWith("backend-control:")) {
            return "control channel (" + LogText.clean(ownerId.substring("backend-control:".length())) + ")";
        }
        return "plugin " + LogText.clean(ownerId);
    }

    public static String duration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h " + minutes % 60 + "m";
        return hours / 24 + "d " + hours % 24 + "h";
    }

    private static String hostPort(URI address) {
        return address == null ? "<unknown>" : LogText.clean(address.getHost() + ":" + address.getPort());
    }
}

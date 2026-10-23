package dev.moonbridge.core.admin;

import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.core.backend.BackendCatalog;
import dev.moonbridge.core.backend.BackendView;
import dev.moonbridge.core.log.ConnectionLog;
import dev.moonbridge.core.log.RecentActivity;
import dev.moonbridge.core.session.OnlinePlayer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Read-only snapshots of the proxy for the admin console: who is online, which servers exist and how busy the proxy
 * has been. Every method builds a fresh immutable answer from thread-safe sources, so it can run on the console thread
 * without touching session state on the network threads.
 */
public final class AdminView {
    /** {@code identity} names this connection, so actions on it cannot reach a later reconnect. */
    public record PlayerRow(String name, PlayerIdentity identity, Optional<String> server, Duration online,
                            String address, String via) { }

    public record ServerRow(String name, String address, int players, String source) { }

    public record Status(String version, Duration uptime, String transport, List<String> listeners, int players,
                         int servers, long heapUsedBytes, long heapMaxBytes, RecentActivity activity) { }

    /** What does not change while the proxy runs. */
    public record Identity(String version, Instant started, String transport, List<String> listeners) {
        public Identity {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(started, "started");
            Objects.requireNonNull(transport, "transport");
            listeners = List.copyOf(listeners);
        }
    }

    private static final Comparator<PlayerRow> BY_NAME = Comparator.comparing(row -> row.name().toLowerCase(Locale.ROOT));

    private final Supplier<List<OnlinePlayer>> players;
    private final BackendCatalog catalog;
    private final Supplier<RecentActivity> activity;
    private final Identity identity;
    private final Clock clock;

    public AdminView(Supplier<List<OnlinePlayer>> players, BackendCatalog catalog, Supplier<RecentActivity> activity,
                     Identity identity, Clock clock) {
        this.players = Objects.requireNonNull(players, "players");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Online players sorted by name. */
    public List<PlayerRow> players() {
        Instant now = clock.instant();
        return players.get().stream().map(player -> row(player, now)).sorted(BY_NAME).toList();
    }

    /** The online player with this name, ignoring case. */
    public Optional<PlayerRow> player(String name) {
        Objects.requireNonNull(name, "name");
        return players().stream().filter(row -> row.name().equalsIgnoreCase(name)).findFirst();
    }

    /** Catalog servers sorted by name, each with the number of online players on it. */
    public List<ServerRow> servers() {
        Map<String, Integer> counts = new HashMap<>();
        for (OnlinePlayer player : players.get()) {
            player.view().currentServer().ifPresent(server -> counts.merge(server, 1, Integer::sum));
        }
        return catalog.snapshot().stream()
                .sorted(Comparator.comparing(view -> view.handle().id().value()))
                .map(view -> new ServerRow(view.handle().id().value(), hostPort(view),
                        counts.getOrDefault(view.handle().id().value(), 0), ConnectionLog.owner(view.owner().id())))
                .toList();
    }

    public Status status() {
        Runtime runtime = Runtime.getRuntime();
        return new Status(identity.version(), Duration.between(identity.started(), clock.instant()),
                identity.transport(), identity.listeners(), players.get().size(), catalog.snapshot().size(),
                runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), activity.get());
    }

    private static PlayerRow row(OnlinePlayer player, Instant now) {
        var view = player.view();
        String address = player.address() == null ? "<unknown>"
                : player.address().getAddress() == null ? player.address().getHostString()
                : player.address().getAddress().getHostAddress();
        return new PlayerRow(view.username(), view.identity(), view.currentServer(),
                Duration.between(player.connectedAt(), now), address, player.webSocket() ? "WebSocket" : "TCP");
    }

    private static String hostPort(BackendView view) {
        return view.address().getHost() + ":" + view.address().getPort();
    }
}

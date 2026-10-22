package dev.moonbridge.app.console;

import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.TransferStatus;
import dev.moonbridge.core.admin.AdminView;
import dev.moonbridge.core.log.ConnectionLog;
import dev.moonbridge.core.log.LogText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The proxy's own console commands. They run before plugin console commands, and print to the console only; actions
 * that change something (kick, send, loglevel, stop) are also logged to {@code moonbridge.admin}.
 */
public final class BuiltinCommands {
    public static final List<String> NAMES =
            List.of("help", "list", "servers", "player", "status", "send", "kick", "loglevel", "stop");

    /** Short names for the loggers an operator usually wants to adjust; any full logger name works too. */
    static final Map<String, String> LOG_AREAS = areas();
    static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");

    private static final Logger ADMIN = LoggerFactory.getLogger("moonbridge.admin");
    private static final String DEFAULT_KICK_REASON = "Disconnected by an operator.";

    private final AdminView view;
    private final AdminActions actions;
    private final Consumer<String> out;

    public BuiltinCommands(AdminView view, AdminActions actions, Consumer<String> out) {
        this.view = Objects.requireNonNull(view, "view");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.out = Objects.requireNonNull(out, "out");
    }

    /** Runs a built-in command; false when the line names none, so plugin commands get it. */
    public boolean run(String line) {
        List<String> words = words(line);
        if (words.isEmpty()) return false;
        List<String> args = words.subList(1, words.size());
        switch (words.getFirst().toLowerCase(Locale.ROOT)) {
            case "help" -> help();
            case "list" -> list();
            case "servers" -> servers();
            case "player" -> player(args);
            case "status" -> status();
            case "send" -> send(args);
            case "kick" -> kick(args);
            case "loglevel" -> logLevel(args);
            case "stop" -> stop();
            default -> { return false; }
        }
        return true;
    }

    /** Candidates for the word at {@code index} of {@code words} (the word being typed may be empty). */
    public List<String> complete(List<String> words, int index) {
        if (index == 0) return NAMES;
        String command = words.getFirst().toLowerCase(Locale.ROOT);
        return switch (command) {
            case "player", "kick" -> index == 1 ? playerNames() : List.of();
            case "send" -> index == 1 ? playerNames()
                    : index == 2 ? view.servers().stream().map(AdminView.ServerRow::name).toList() : List.of();
            case "loglevel" -> index == 1 ? List.copyOf(LOG_AREAS.keySet())
                    : index == 2 ? LEVELS : List.of();
            default -> List.of();
        };
    }

    private void help() {
        out.accept("Built-in commands:");
        out.accept("  list                          players online, by server");
        out.accept("  servers                       servers, their players and who registered them");
        out.accept("  player <name>                 one player's connection");
        out.accept("  status                        version, uptime, load and the last 10 minutes");
        out.accept("  send <player> <server>        move a player to another server");
        out.accept("  kick <player> [reason]        disconnect a player");
        out.accept("  loglevel [<area> <level>]     show or change log detail at runtime");
        out.accept("  stop                          shut down, same as Ctrl+C");
        out.accept("Other words are passed to plugin console commands.");
    }

    private void list() {
        List<AdminView.PlayerRow> players = view.players();
        out.accept(plural(players.size(), "player") + " online");
        Map<String, List<String>> byServer = new TreeMap<>();
        for (AdminView.PlayerRow player : players) {
            byServer.computeIfAbsent(player.server().orElse("(moving)"), server -> new ArrayList<>()).add(player.name());
        }
        byServer.forEach((server, names) ->
                out.accept("  " + LogText.clean(server) + " (" + names.size() + "): "
                        + names.stream().map(LogText::clean).collect(Collectors.joining(", "))));
    }

    private void servers() {
        List<AdminView.ServerRow> servers = view.servers();
        if (servers.isEmpty()) {
            out.accept("No servers are registered.");
            return;
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"NAME", "ADDRESS", "PLAYERS", "SOURCE"});
        for (AdminView.ServerRow server : servers) {
            rows.add(new String[]{LogText.clean(server.name()), LogText.clean(server.address()),
                    Integer.toString(server.players()), server.source()});
        }
        table(rows);
    }

    private void player(List<String> args) {
        if (args.size() != 1) {
            out.accept("Usage: player <name>");
            return;
        }
        Optional<AdminView.PlayerRow> found = view.player(args.getFirst());
        if (found.isEmpty()) {
            out.accept("No online player named " + LogText.clean(args.getFirst()) + ".");
            return;
        }
        AdminView.PlayerRow player = found.get();
        out.accept(LogText.clean(player.name()) + " (" + player.identity().playerId() + ") via " + player.via()
                + " from " + player.address());
        out.accept("  " + player.server().map(server -> "on " + LogText.clean(server)).orElse("between servers")
                + ", online " + ConnectionLog.duration(player.online()));
    }

    private void status() {
        AdminView.Status status = view.status();
        out.accept("MoonBridge " + status.version() + " · up " + ConnectionLog.duration(status.uptime()) + " · "
                + status.transport() + " · " + String.join(", ", status.listeners()));
        out.accept(plural(status.players(), "player") + " · " + plural(status.servers(), "server") + " · heap "
                + status.heapUsedBytes() / (1024 * 1024) + "/" + status.heapMaxBytes() / (1024 * 1024) + " MB");
        var recent = status.activity();
        out.accept("Last 10 min: " + recent.joins() + " joins · " + recent.leaves() + " leaves · " + recent.refused()
                + " refused · " + recent.moves() + " moves, " + recent.failedMoves() + " failed · "
                + recent.overloads() + " overload rejections");
    }

    private void send(List<String> args) {
        if (args.size() != 2) {
            out.accept("Usage: send <player> <server>");
            return;
        }
        Optional<AdminView.PlayerRow> player = view.player(args.get(0));
        if (player.isEmpty()) {
            out.accept("No online player named " + LogText.clean(args.get(0)) + ".");
            return;
        }
        String server = args.get(1);
        if (view.servers().stream().noneMatch(row -> row.name().equals(server))) {
            out.accept("No server named " + LogText.clean(server) + ". Type servers to list them.");
            return;
        }
        String name = LogText.clean(player.get().name());
        ADMIN.info("Console sends {} to {}", name, LogText.clean(server));
        actions.send(player.get().identity(), server).whenComplete((result, failure) -> {
            if (failure != null) out.accept("Could not send " + name + ": " + LogText.clean(failure.getMessage()));
            else if (result.status() == TransferStatus.NETWORK_READY) out.accept(name + " is on " + LogText.clean(server) + ".");
            else out.accept("Could not send " + name + ": " + LogText.clean(result.detail()
                    .orElse(result.status().name().toLowerCase(Locale.ROOT).replace('_', ' '))));
        });
    }

    private void kick(List<String> args) {
        if (args.isEmpty()) {
            out.accept("Usage: kick <player> [reason]");
            return;
        }
        Optional<AdminView.PlayerRow> player = view.player(args.getFirst());
        if (player.isEmpty()) {
            out.accept("No online player named " + LogText.clean(args.getFirst()) + ".");
            return;
        }
        String reason = args.size() == 1 ? DEFAULT_KICK_REASON : String.join(" ", args.subList(1, args.size()));
        String name = LogText.clean(player.get().name());
        ADMIN.info("Console kicks {}: {}", name, LogText.clean(reason));
        actions.kick(player.get().identity(), reason).whenComplete((result, failure) -> {
            if (failure != null) out.accept("Could not kick " + name + ": " + LogText.clean(failure.getMessage()));
            else if (result != DisconnectResult.DISCONNECTED) out.accept(name + " is no longer online.");
        });
    }

    private void logLevel(List<String> args) {
        if (args.isEmpty()) {
            out.accept("Log areas (or any logger name) and their levels:");
            LOG_AREAS.forEach((area, logger) ->
                    out.accept("  " + pad(area, 10) + pad(actions.logLevel(logger), 7) + logger));
            out.accept("Usage: loglevel <area> <" + String.join("|", LEVELS) + ">");
            return;
        }
        if (args.size() != 2) {
            out.accept("Usage: loglevel <area> <level>");
            return;
        }
        String logger = LOG_AREAS.getOrDefault(args.get(0).toLowerCase(Locale.ROOT), args.get(0));
        String level = args.get(1).toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(level)) {
            out.accept("Unknown level " + LogText.clean(args.get(1)) + "; use one of " + String.join(", ", LEVELS) + ".");
            return;
        }
        actions.setLogLevel(logger, level);
        ADMIN.info("Console set the log level of {} to {}", LogText.clean(logger), level);
        out.accept(LogText.clean(logger) + " now logs at " + level + " until the proxy restarts.");
    }

    private void stop() {
        ADMIN.info("Stop requested from the console");
        actions.stop();
    }

    private List<String> playerNames() {
        return view.players().stream().map(AdminView.PlayerRow::name).toList();
    }

    private void table(List<String[]> rows) {
        int[] widths = new int[rows.getFirst().length];
        for (String[] row : rows) for (int i = 0; i < row.length; i++) widths[i] = Math.max(widths[i], row[i].length());
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                line.append(i == row.length - 1 ? row[i] : pad(row[i], widths[i] + 2));
            }
            out.accept(line.toString());
        }
    }

    private static String plural(long count, String noun) { return count + " " + noun + (count == 1 ? "" : "s"); }

    private static String pad(String text, int width) {
        return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
    }

    static List<String> words(String line) {
        String trimmed = line.strip();
        return trimmed.isEmpty() ? List.of() : Arrays.asList(trimmed.split("\\s+"));
    }

    private static Map<String, String> areas() {
        Map<String, String> areas = new LinkedHashMap<>();
        areas.put("players", ConnectionLog.PLAYERS);
        areas.put("servers", ConnectionLog.SERVERS);
        areas.put("plugins", "moonbridge.plugins");
        areas.put("admin", "moonbridge.admin");
        areas.put("session", "dev.moonbridge.core.session");
        areas.put("transfer", "dev.moonbridge.core.session.transfer");
        areas.put("websocket", "dev.moonbridge.core.websocket");
        areas.put("control", "dev.moonbridge.core.control");
        areas.put("netty", "io.netty");
        areas.put("root", "ROOT");
        return java.util.Collections.unmodifiableMap(areas);
    }
}

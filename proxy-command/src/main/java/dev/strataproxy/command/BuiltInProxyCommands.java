package dev.strataproxy.command;

import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.command.CommandResult;
import dev.strataproxy.plugin.command.CommandSpec;
import dev.strataproxy.plugin.service.PlayerService;
import dev.strataproxy.plugin.service.ServerService;
import dev.strataproxy.plugin.service.ServerView;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class BuiltInProxyCommands {
    private BuiltInProxyCommands() {
    }

    public static void register(CommandRegistry commands, PlayerService players, ServerService servers) {
        commands.register(new CommandSpec(
                "server",
                List.of("send"),
                "",
                "Transfer to another backend server.",
                context -> {
                    if (context.arguments().size() != 1) {
                        return CompletableFuture.completedFuture(CommandResult.failure("Usage: /server <server>"));
                    }
                    var target = context.arguments().get(0);
                    return players.transfer(context.source().name(), target)
                            .thenApply(result -> result.success()
                                    ? CommandResult.ok("Connecting to " + result.targetServer() + ".")
                                    : CommandResult.failure("Transfer failed: " + result.outcome()));
                }));

        commands.register(new CommandSpec(
                "hub",
                List.of("lobby"),
                "",
                "Transfer to the first lobby or hub server.",
                context -> {
                    var target = hubTarget(servers);
                    if (target == null) {
                        return CompletableFuture.completedFuture(CommandResult.failure("No hub or lobby server is available."));
                    }
                    return players.transfer(context.source().name(), target.name())
                            .thenApply(result -> result.success()
                                    ? CommandResult.ok("Connecting to " + result.targetServer() + ".")
                                    : CommandResult.failure("Transfer failed: " + result.outcome()));
                }));

        commands.register(new CommandSpec(
                "servers",
                List.of("glist"),
                "",
                "List registered backend servers.",
                context -> {
                    var names = servers.servers().stream()
                            .map(ServerView::name)
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .toList();
                    var message = names.isEmpty() ? "No backend servers are registered." : "Servers: " + String.join(", ", names);
                    return CompletableFuture.completedFuture(CommandResult.ok(message));
                }));
    }

    private static ServerView hubTarget(ServerService servers) {
        var tagged = servers.firstWithTag("lobby").or(() -> servers.firstWithTag("hub"));
        if (tagged.isPresent()) {
            return tagged.get();
        }
        return servers.servers().stream()
                .filter(server -> {
                    var name = server.name().toLowerCase(Locale.ROOT);
                    return name.equals("hub") || name.equals("lobby") || name.startsWith("hub-") || name.startsWith("lobby-");
                })
                .findFirst()
                .orElse(null);
    }
}

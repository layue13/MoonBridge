package dev.moonbridge.luckperms;

import dev.moonbridge.api.PlayerView;
import me.lucko.luckperms.common.context.manager.SimpleContextManager;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.context.MutableContextSet;
import net.luckperms.api.query.QueryOptions;
import java.util.UUID;

/** Maps MoonBridge's current backend into LuckPerms' standard world context. */
final class MoonBridgeContextManager extends SimpleContextManager<MoonBridgePlayer, MoonBridgePlayer> {
    private final LuckPermsPlugin plugin;
    private final String proxyId;

    MoonBridgeContextManager(LuckPermsPlugin plugin, String proxyId) {
        super(plugin, MoonBridgePlayer.class, MoonBridgePlayer.class);
        this.plugin = plugin;
        this.proxyId = proxyId;
        registerCalculator((player, consumer) -> {
            PlayerView view = player.view();
            view.currentServer().ifPresent(server -> {
                consumer.accept("backend", server);
                plugin.getConfiguration().get(me.lucko.luckperms.common.config.ConfigKeys.WORLD_REWRITES).rewriteAndSubmit(server, consumer);
            });
            if (this.proxyId != null && !this.proxyId.isBlank()) consumer.accept("proxy", this.proxyId);
        });
    }

    @Override public UUID getUniqueId(MoonBridgePlayer player) { return player.view().identity().playerId(); }

    QueryOptions queryOptions(MoonBridgePlayer player) { return getQueryOptions(player); }

    QueryOptions overlay(QueryOptions base, dev.moonbridge.api.permission.PermissionContext explicit) {
        MutableContextSet context = base.context().mutableCopy();
        boolean backendExplicit = explicit.values().containsKey("backend");
        boolean worldExplicit = explicit.values().containsKey("world");
        // A backend override replaces the world context derived from the current backend too.
        if (backendExplicit && !worldExplicit) context.removeAll("world");
        explicit.values().forEach((key, values) -> {
            context.removeAll(key);
            values.forEach(value -> context.add(key, value));
        });
        if (backendExplicit && !worldExplicit) {
            explicit.values().get("backend").forEach(backend -> plugin.getConfiguration()
                    .get(me.lucko.luckperms.common.config.ConfigKeys.WORLD_REWRITES)
                    .rewriteAndSubmit(backend, context::add));
        }
        return base.toBuilder().context(ImmutableContextSet.builder().addAll(context).build()).build();
    }
}

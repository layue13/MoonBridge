package dev.moonbridge.luckperms;

import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.permission.PermissionContext;
import dev.moonbridge.api.permission.PermissionDecision;
import dev.moonbridge.api.permission.PermissionSubject;
import me.lucko.luckperms.common.model.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Session-scoped adapter over LuckPerms' cached user data. */
final class MoonBridgePermissionSubject implements PermissionSubject {
    private final LuckPermsMoonBridgePlugin owner;
    private final PlayerIdentity identity;
    private final User user;
    private final MoonBridgePlayer player;
    private final ApiUserLeaseTracker.Lease<net.luckperms.api.model.user.User> lease;
    private final AtomicBoolean closed = new AtomicBoolean();

    MoonBridgePermissionSubject(LuckPermsMoonBridgePlugin owner, PlayerView view, User user,
                                ApiUserLeaseTracker.Lease<net.luckperms.api.model.user.User> lease) {
        this.owner = owner;
        this.identity = view.identity();
        this.user = user;
        this.player = new MoonBridgePlayer(view);
        this.lease = lease;
    }

    PlayerIdentity identity() { return identity; }
    MoonBridgePlayer player() { return player; }
    ApiUserLeaseTracker.Lease<net.luckperms.api.model.user.User> lease() { return lease; }

    @Override
    public PermissionDecision check(String node, PermissionContext explicit) {
        if (closed.get() || !owner.isCurrent(identity)) return PermissionDecision.DENY;
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(explicit, "context");
        QueryOptions query = owner.contextManager().overlay(owner.contextManager().queryOptions(player), explicit);
        Tristate result = user.getCachedData().getPermissionData(query).queryPermission(node).result();
        return switch (result) {
            case TRUE -> PermissionDecision.ALLOW;
            case FALSE -> PermissionDecision.DENY;
            case UNDEFINED -> PermissionDecision.UNDEFINED;
        };
    }

    @Override
    public void update(PlayerView view) {
        if (closed.get() || !owner.isCurrent(identity) || !identity.equals(view.identity())) return;
        player.update(view);
        owner.contextManager().signalContextUpdate(player);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        owner.release(this);
    }
}

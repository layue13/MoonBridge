package dev.moonbridge.bukkit;

import dev.moonbridge.messaging.Messaging;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** Bukkit ownership adapter; the injected factory always uses the host's one client. */
final class OwnedMessagingService implements BukkitMessagingService, AutoCloseable {
    interface ScopeFactory { Messaging open(String owner, Executor executor); }

    private final ScopeFactory scopes;
    private final Function<Plugin, Executor> executors;
    private final Map<Plugin, Messaging> owners = new IdentityHashMap<Plugin, Messaging>();
    private boolean closed;

    OwnedMessagingService(ScopeFactory scopes, Function<Plugin, Executor> executors) {
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    @Override public synchronized Messaging forPlugin(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        if (closed) throw new IllegalStateException("MoonBridge messaging host is disabled");
        if (!owner.isEnabled()) throw new IllegalStateException("Message owner is not enabled");
        Messaging existing = owners.get(owner);
        if (existing != null) return existing;
        Messaging scope = scopes.open("bukkit:" + owner.getName(), executors.apply(owner));
        owners.put(owner, scope);
        return scope;
    }

    void release(Plugin owner) {
        Messaging scope;
        synchronized (this) { scope = owners.remove(owner); }
        if (scope != null) scope.close();
    }

    @Override public void close() {
        ArrayList<Messaging> snapshot;
        synchronized (this) {
            if (closed) return;
            closed = true;
            snapshot = new ArrayList<Messaging>(owners.values());
            owners.clear();
        }
        for (Messaging scope : snapshot) scope.close();
    }
}

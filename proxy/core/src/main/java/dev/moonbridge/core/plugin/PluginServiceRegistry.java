package dev.moonbridge.core.plugin;

import dev.moonbridge.api.ServiceRegistration;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The host's table of plugin services: one provider per type, visible to the provider and its dependents. */
final class PluginServiceRegistry {
    private record Entry(PluginContextImpl owner, Object service) { }

    private final Map<Class<?>, Entry> entries = new ConcurrentHashMap<>();

    <T> ServiceRegistration register(PluginContextImpl owner, Class<T> type, T service) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(service, "service");
        if (!type.isInstance(service)) {
            throw new IllegalArgumentException(service.getClass().getName() + " does not implement " + type.getName());
        }
        Entry entry = new Entry(owner, service);
        Entry existing = entries.putIfAbsent(type, entry);
        if (existing != null) {
            throw new IllegalStateException(type.getName() + " is already provided by plugin " + existing.owner.displayName());
        }
        return () -> entries.remove(type, entry);
    }

    <T> Optional<T> find(PluginContextImpl caller, Class<T> type) {
        Objects.requireNonNull(type, "type");
        Entry entry = entries.get(type);
        if (entry == null || (entry.owner != caller && !caller.dependsOn(entry.owner))) return Optional.empty();
        return Optional.of(type.cast(entry.service));
    }

    void removeOwner(PluginContextImpl owner) {
        entries.values().removeIf(entry -> entry.owner == owner);
    }
}

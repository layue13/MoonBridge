package dev.strataproxy.app;

import dev.strataproxy.api.server.DrainPolicy;
import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerCapability;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerRegistry;
import dev.strataproxy.registry.RegistryPersistenceService;
import dev.strataproxy.plugin.service.Scheduler;
import dev.strataproxy.plugin.service.ServerMutationResult;
import dev.strataproxy.plugin.service.ServerHealthView;
import dev.strataproxy.plugin.service.ServerLoadView;
import dev.strataproxy.plugin.service.ServerPersistence;
import dev.strataproxy.plugin.service.ServerProtocolRange;
import dev.strataproxy.plugin.service.ServerRegistration;
import dev.strataproxy.plugin.service.ServerRemoval;
import dev.strataproxy.plugin.service.ServerView;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/** Adapts the registry to the ownership-scoped plugin server SPI. */
final class PluginServerService implements dev.strataproxy.plugin.service.ServerService {
    private static final String SOURCE_KEY = "strataproxy.source";
    private static final String SOURCE_PLUGIN = "plugin";
    private static final String PLUGIN_ID_KEY = "strataproxy.pluginId";
    private static final String PERSISTENCE_KEY = "strataproxy.persistence";

    private final ServerRegistry registry;
    private final RegistryPersistenceService registryPersistence;
    private final Scheduler scheduler;
    private final String pluginId;

    PluginServerService(
            ServerRegistry registry,
            RegistryPersistenceService registryPersistence,
            Scheduler scheduler,
            String pluginId) {
        this.registry = registry;
        this.registryPersistence = registryPersistence;
        this.scheduler = scheduler;
        this.pluginId = pluginId == null || pluginId.isBlank() ? "unknown" : pluginId.trim();
    }

    @Override
    public Optional<ServerView> find(String serverName) {
        if (serverName == null || serverName.isBlank()) return Optional.empty();
        return registry.get(serverName.trim()).map(PluginServerService::view);
    }

    @Override
    public Optional<ServerView> firstWithTag(String tag) {
        if (tag == null || tag.isBlank()) return Optional.empty();
        return registry.snapshot().stream()
                .map(PluginServerService::view)
                .filter(server -> server.tags().stream().anyMatch(value -> value.equalsIgnoreCase(tag)))
                .findFirst();
    }

    @Override
    public Collection<ServerView> servers() {
        return registry.snapshot().stream().map(PluginServerService::view).toList();
    }

    @Override
    public CompletionStage<ServerMutationResult> register(ServerRegistration registration) {
        return mutate(() -> {
            if (registration == null) {
                return ServerMutationResult.failure("invalid_request", "registration must not be null");
            }
            var ownershipFailure = ownershipFailure(registration.name());
            if (ownershipFailure != null) return ownershipFailure;
            var descriptor = descriptor(registration);
            var server = registration.persistence() == ServerPersistence.PERSISTENT
                    ? registryPersistence.register(descriptor)
                    : registry.registerOrReplace(descriptor);
            return ServerMutationResult.success("registered", view(server));
        });
    }

    @Override
    public CompletionStage<ServerMutationResult> unregister(String serverName, ServerRemoval removal) {
        return mutate(() -> {
            var name = normalizeName(serverName);
            var existing = registry.get(name);
            if (existing.isEmpty()) {
                return ServerMutationResult.failure("not_found", "server is not registered: " + name);
            }
            var ownershipFailure = ownershipFailure(name);
            if (ownershipFailure != null) return ownershipFailure;
            var request = removal == null ? ServerRemoval.ephemeral() : removal;
            var policy = new DrainPolicy(
                    request.rejectNewConnections(), request.migrateExistingPlayers(), request.gracePeriod());
            var removed = request.persistence() == ServerPersistence.PERSISTENT
                    ? registryPersistence.unregister(name, policy)
                    : registry.unregister(name, policy);
            return removed
                    ? ServerMutationResult.success("unregistered", view(existing.get()))
                    : ServerMutationResult.failure("not_found", "server is not registered: " + name);
        });
    }

    @Override
    public CompletionStage<ServerMutationResult> setDrainMode(
            String serverName, boolean drainMode, ServerPersistence persistence) {
        return mutate(() -> {
            var name = normalizeName(serverName);
            if (registry.get(name).isEmpty()) {
                return ServerMutationResult.failure("not_found", "server is not registered: " + name);
            }
            var ownershipFailure = ownershipFailure(name);
            if (ownershipFailure != null) return ownershipFailure;
            if (persistence == ServerPersistence.PERSISTENT) {
                if (!registryPersistence.updateDrainMode(name, drainMode)) {
                    return ServerMutationResult.failure("not_found", "server is not registered: " + name);
                }
            } else {
                registry.updateDrainMode(name, drainMode);
            }
            return registry.get(name)
                    .map(server -> ServerMutationResult.success(drainMode ? "drained" : "undrained", view(server)))
                    .orElseGet(() -> ServerMutationResult.failure("not_found", "server is not registered: " + name));
        });
    }

    private CompletionStage<ServerMutationResult> mutate(Mutation mutation) {
        var result = new CompletableFuture<ServerMutationResult>();
        try {
            scheduler.runAsync(() -> {
                try {
                    result.complete(mutation.run());
                } catch (IllegalArgumentException exception) {
                    result.complete(ServerMutationResult.failure("invalid_request", exception.getMessage()));
                } catch (Exception exception) {
                    result.complete(ServerMutationResult.failure("mutation_failed", rootMessage(exception)));
                }
            }).whenComplete((ignored, exception) -> {
                if (exception != null) {
                    result.complete(ServerMutationResult.failure("mutation_failed", rootMessage(exception)));
                }
            });
        } catch (RuntimeException exception) {
            result.complete(ServerMutationResult.failure("mutation_failed", rootMessage(exception)));
        }
        return result;
    }

    private ServerDescriptor descriptor(ServerRegistration registration) {
        var metadata = new LinkedHashMap<>(registration.metadata());
        metadata.put(SOURCE_KEY, SOURCE_PLUGIN);
        metadata.put(PLUGIN_ID_KEY, pluginId);
        metadata.put(PERSISTENCE_KEY, registration.persistence().name().toLowerCase(Locale.ROOT));
        return new ServerDescriptor(
                registration.name(), registration.address(), registration.tags(), capabilities(registration.capabilities()),
                new ProtocolRange(registration.protocolRange().minProtocol(), registration.protocolRange().maxProtocol(),
                        registration.protocolRange().displayName()),
                registration.weight(), registration.softCapacity(), registration.hardCapacity(), registration.drainMode(), metadata);
    }

    private ServerMutationResult ownershipFailure(String serverName) {
        var name = normalizeName(serverName);
        var existing = registry.get(name);
        if (existing.isEmpty()) return null;
        var metadata = existing.get().descriptor().metadata();
        if (SOURCE_PLUGIN.equals(metadata.get(SOURCE_KEY)) && pluginId.equals(metadata.get(PLUGIN_ID_KEY))) return null;
        if (SOURCE_PLUGIN.equals(metadata.get(SOURCE_KEY))) {
            return ServerMutationResult.failure("server_owned_by_other_plugin",
                    "server is owned by plugin: " + metadata.getOrDefault(PLUGIN_ID_KEY, ""));
        }
        return ServerMutationResult.failure("server_not_owned", "server is not owned by plugin: " + pluginId);
    }

    private static Set<ServerCapability> capabilities(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT))
                .map(ServerCapability::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeName(String serverName) {
        if (serverName == null || serverName.isBlank()) throw new IllegalArgumentException("server name must not be blank");
        return serverName.trim();
    }

    private static ServerView view(RegisteredServer server) {
        var descriptor = server.descriptor();
        var health = server.health();
        var load = server.load();
        return new ServerView(descriptor.name(), descriptor.address(), descriptor.tags(), descriptor.drainMode(),
                descriptor.softCapacity(), descriptor.hardCapacity(),
                descriptor.capabilities().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()),
                new ServerProtocolRange(descriptor.protocolRange().minProtocol(),
                        descriptor.protocolRange().maxProtocol(), descriptor.protocolRange().displayName()),
                descriptor.weight(), descriptor.metadata(),
                new ServerHealthView(ServerHealthView.Status.valueOf(health.status().name()),
                        health.backendPingMillis(), health.recentFailureRate(), health.reason(), health.updatedAt()),
                new ServerLoadView(load.players(), load.inboundBytesPerSecond(),
                        load.outboundBytesPerSecond()));
    }

    private static String rootMessage(Throwable throwable) {
        var current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @FunctionalInterface
    private interface Mutation {
        ServerMutationResult run() throws Exception;
    }
}

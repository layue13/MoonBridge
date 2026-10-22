package dev.moonbridge.core.plugin;

import dev.moonbridge.api.MessageResult;
import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.event.Event;
import dev.moonbridge.api.event.EventListener;
import dev.moonbridge.api.event.EventSubscription;
import dev.moonbridge.api.event.Events;
import dev.moonbridge.messaging.Messaging;
import dev.moonbridge.api.AsyncCommandHandler;
import dev.moonbridge.api.CommandHandler;
import dev.moonbridge.api.CommandCompleter;
import dev.moonbridge.api.CommandRegistration;
import dev.moonbridge.api.CommandRegistrationOptions;
import dev.moonbridge.api.CommandSource;
import dev.moonbridge.api.Commands;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.Players;
import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.api.ServerDefinition;
import dev.moonbridge.api.ServerRegistration;
import dev.moonbridge.api.ServerView;
import dev.moonbridge.api.Servers;
import dev.moonbridge.api.TransferResult;
import dev.moonbridge.api.permission.PermissionResult;
import net.kyori.adventure.text.Component;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendOwner;
import dev.moonbridge.core.backend.BackendRegistration;
import dev.moonbridge.core.backend.BackendView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.UUID;

    final class PluginContextImpl implements PluginContext {
    private final HostServices host;
        final BackendOwner owner;
        private final Messaging pluginMessaging;
        private final Players pluginPlayers;
        private final PluginServers servers;
        private final Commands pluginCommands;
        private final Events pluginEvents;
        private final dev.moonbridge.api.permission.Permissions pluginPermissions;
        private final Logger logger;
        private final Map<String, String> settings;
        private final Path dataDirectory;
        private final PluginClassLoader pluginClassLoader;
        final Set<EventRegistration<?, ?>> eventSubscriptions = new HashSet<>();
        volatile boolean active = true;
        private boolean eventRegistrationOpen;

        PluginContextImpl(HostServices host, BackendOwner owner, Map<String, String> settings, Path dataDirectory,
                          PluginClassLoader pluginClassLoader) {
            this.host = host;
            this.owner = owner;
            this.pluginMessaging = host.messaging().openScope(owner.id());
            this.pluginPlayers = new PluginPlayers(this);
            this.servers = new PluginServers(this);
            this.pluginCommands = new PluginCommands(this);
            this.pluginEvents = new PluginEvents(this);
            this.pluginPermissions = new dev.moonbridge.api.permission.Permissions() {
                @Override public PermissionResult check(PlayerIdentity identity, String node) {
                    synchronized (PluginContextImpl.this) {
                        if (!active) return PermissionResult.UNAVAILABLE;
                        return host.permissions().check(identity, node);
                    }
                }
                @Override public PermissionResult check(PlayerIdentity identity, String node,
                                                        dev.moonbridge.api.permission.PermissionContext permissionContext) {
                    synchronized (PluginContextImpl.this) {
                        if (!active) return PermissionResult.UNAVAILABLE;
                        return host.permissions().check(identity, node, permissionContext);
                    }
                }
            };
            this.logger = LoggerFactory.getLogger("plugin." + owner.id());
            this.settings = Map.copyOf(settings);
            this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
            this.pluginClassLoader = pluginClassLoader;
        }

        @Override public UUID proxyEpoch() { return host.proxyEpoch(); }

        @Override public Players players() { return pluginPlayers; }
        @Override public Servers servers() { return servers; }
        @Override public Commands commands() { return pluginCommands; }
        @Override public Events events() { return pluginEvents; }
        @Override public Messaging messaging() { return pluginMessaging; }
        @Override public Logger logger() { return logger; }
        @Override public dev.moonbridge.api.permission.Permissions permissions() { return pluginPermissions; }
        @Override public Path dataDirectory() {
            synchronized (this) { requireActive(); return dataDirectory; }
        }
        @Override public void addLibrary(Path jar) throws IOException {
            synchronized (this) {
                requireActive();
                if (pluginClassLoader == null) {
                    throw new UnsupportedOperationException("Dynamic libraries require a JAR-loaded plugin");
                }
                pluginClassLoader.addLibrary(jar, dataDirectory);
            }
        }
        @Override public Map<String, String> settings() { return settings; }

        synchronized void deactivateAndRemove() {
            active = false;
            eventRegistrationOpen = false;
            pluginMessaging.close();
            revokeEventSubscriptions();
            host.commands().removeOwner(this);
            host.catalog().removeOwner(owner);
        }

        synchronized void revokeEventSubscriptions() {
            eventRegistrationOpen = false;
            eventSubscriptions.forEach(EventRegistration::close);
            eventSubscriptions.clear();
        }

        synchronized void beginEventRegistration() {
            if (!active) throw new IllegalStateException("Plugin context is inactive");
            eventRegistrationOpen = true;
        }

        synchronized void endEventRegistration() { eventRegistrationOpen = false; }

        synchronized void requireEventRegistrationOpen() {
            requireActive();
            if (!eventRegistrationOpen || !host.lifecycle().loading()) {
                throw new IllegalStateException("Event subscriptions are only allowed during onLoad or onEnable");
            }
        }

        void requireActive() {
            if (!active) throw new IllegalStateException("Plugin context is inactive");
        }

    private final class PluginEvents implements Events {
        private final PluginContextImpl context;

        private PluginEvents(PluginContextImpl context) { this.context = context; }

        @Override public <R, E extends Event<R>> EventSubscription subscribe(
                Class<E> eventType, EventListener<E, R> listener) {
            return host.events().register(context, eventType, listener);
        }
    }

    private final class PluginCommands implements Commands {
        private final PluginContextImpl context;

        private PluginCommands(PluginContextImpl context) { this.context = context; }

        @Override public CommandRegistration register(String name, CommandHandler handler, CommandCompleter completer) {
            return register(name, CommandRegistrationOptions.defaults(), handler, completer);
        }

        @Override public CommandRegistration register(String name, CommandRegistrationOptions options,
                                                       CommandHandler handler, CommandCompleter completer) {
            Objects.requireNonNull(handler, "handler");
            return registerAsync(name, options, invocation -> {
                handler.execute(invocation);
                return CompletableFuture.completedFuture(null);
            }, completer);
        }

        @Override public CommandRegistration registerAsync(String name, CommandRegistrationOptions options,
                                                            AsyncCommandHandler handler,
                                                            CommandCompleter completer) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(handler, "handler");
            return host.commands().register(context, name, options, handler, completer);
        }

        @Override public CompletionStage<Boolean> execute(CommandSource source, String command) {
            return host.commands().executeCommand(context, source, command);
        }
    }

    private final class PluginPlayers implements Players {
        private final PluginContextImpl context;

        private PluginPlayers(PluginContextImpl context) {
            this.context = context;
        }

        @Override public Optional<PlayerView> find(PlayerIdentity identity) {
            synchronized (context) {
                context.requireActive();
                return host.players().find(identity);
            }
        }

        @Override public List<PlayerView> online() {
            synchronized (context) {
                context.requireActive();
                return host.players().online();
            }
        }

        @Override public CompletionStage<TransferResult> transfer(PlayerIdentity identity, String backendName) {
            synchronized (context) {
                context.requireActive();
                // CompletableFuture runs dependent callbacks on its completion thread by default.
                // Do not let plugin callbacks execute on the player's Netty event loop.
                return host.players().transfer(identity, backendName)
                        .whenCompleteAsync((ignored, failure) -> { }, PluginThreads.PLAYER_COMPLETIONS);
            }
        }

        @Override public CompletionStage<MessageResult> sendMessage(PlayerIdentity identity, Component message) {
            synchronized (context) {
                context.requireActive();
                return host.players().sendMessage(identity, message)
                        .whenCompleteAsync((ignored, failure) -> { }, PluginThreads.PLAYER_COMPLETIONS);
            }
        }

        @Override public CompletionStage<DisconnectResult> disconnect(PlayerIdentity identity, Component reason) {
            synchronized (context) {
                context.requireActive();
                return host.players().disconnect(identity, reason)
                        .whenCompleteAsync((ignored, failure) -> { }, PluginThreads.PLAYER_COMPLETIONS);
            }
        }
    }

    private final class PluginServers implements Servers {
        private final PluginContextImpl context;
        private final BackendOwner owner;

        private PluginServers(PluginContextImpl context) {
            this.context = context;
            this.owner = context.owner;
        }

        @Override
        public Optional<ServerView> find(String backendName) {
            synchronized (context) {
                context.requireActive();
                Objects.requireNonNull(backendName, "backendName");
                return host.catalog().find(new BackendId(backendName)).map(ServerViews::of);
            }
        }

        @Override
        public List<ServerView> all() {
            synchronized (context) {
                context.requireActive();
                return ServerViews.snapshot(host.catalog());
            }
        }

        @Override
        public ServerRegistration register(ServerDefinition definition) {
            synchronized (context) {
                context.requireActive();
                Objects.requireNonNull(definition, "definition");
                BackendId id = new BackendId(definition.name());
                BackendRegistration registration = new BackendRegistration(id, owner, definition.address(),
                        definition.tags(), definition.metadata());
                BackendView view = host.catalog().register(registration);
                return new PluginServerRegistration(view.handle());
            }
        }

        private final class PluginServerRegistration implements ServerRegistration {
            private final dev.moonbridge.core.backend.BackendHandle handle;
            private boolean active = true;

            private PluginServerRegistration(dev.moonbridge.core.backend.BackendHandle handle) {
                this.handle = handle;
            }

            @Override
            public synchronized void update(ServerDefinition replacement) {
                ensureActive();
                Objects.requireNonNull(replacement, "definition");
                if (!replacement.name().equals(handle.id().value())) {
                    throw new IllegalArgumentException("An update must keep backend name " + handle.id().value());
                }
                BackendRegistration next = new BackendRegistration(handle.id(), owner, replacement.address(),
                        replacement.tags(), replacement.metadata());
                if (host.catalog().update(handle, next).isEmpty()) {
                    active = false;
                    throw new IllegalStateException("Backend registration is stale: " + handle.id().value());
                }
            }

            @Override
            public synchronized void unregister() {
                ensureActive();
                if (!host.catalog().remove(handle)) {
                    active = false;
                    throw new IllegalStateException("Backend registration is stale: " + handle.id().value());
                }
                active = false;
            }

            private void ensureActive() {
                if (!active || !PluginServers.this.context.active) {
                    throw new IllegalStateException("Backend registration is stale: " + handle.id().value());
                }
            }
        }
    }
}

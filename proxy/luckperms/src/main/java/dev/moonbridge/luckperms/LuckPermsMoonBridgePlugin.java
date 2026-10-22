package dev.moonbridge.luckperms;

import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.permission.PermissionProvider;
import dev.moonbridge.api.permission.PermissionSubject;
import me.lucko.luckperms.common.api.implementation.ApiUser;
import me.lucko.luckperms.common.model.User;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.PlayerSaveResult;
import net.luckperms.api.model.data.DataType;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** Native LuckPerms common-engine host for MoonBridge. */
public final class LuckPermsMoonBridgePlugin implements Plugin, PermissionProvider {
    private PluginContext context;
    private MoonBridgeBootstrap bootstrap;
    private MoonBridgePlatform platform;
    private ApiUserLeaseTracker<net.luckperms.api.model.user.User> userLeases;
    private final Object lifecycleLock = new Object();
    private final ConcurrentHashMap<UUID, PlayerIdentity> latestConnection = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MoonBridgePermissionSubject> subjects = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerIdentity, ApiUserLeaseTracker.Lease<net.luckperms.api.model.user.User>> sessionLeases = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile boolean engineEnabled;
    private boolean shutdownStarted;

    @Override
    public void onLoad(PluginContext context) {
        this.context = context;
        this.bootstrap = new MoonBridgeBootstrap(this, context);
        this.platform = new MoonBridgePlatform(this, bootstrap);
        this.userLeases = new ApiUserLeaseTracker<>(user -> {
            try {
                ApiUser.cast(user).clearNodes(DataType.TRANSIENT, null, false);
            } finally {
                platform.getApiProvider().getUserManager().cleanupUser(user);
            }
        });
        try {
            platform.load();
            bootstrap.markLoaded();
        } catch (RuntimeException | Error failure) {
            synchronized (lifecycleLock) { shutdownStarted = true; }
            platform.abortStartup();
            throw failure;
        }
    }

    @Override
    public void onEnable() {
        try {
            platform.enable();
            engineEnabled = true;
            bootstrap.markEnabled();
            synchronized (lifecycleLock) { enabled = true; }
            context.logger().info("LuckPerms native permission provider enabled (" + bootstrap.getVersion() + ")");
        } catch (RuntimeException | Error failure) {
            synchronized (lifecycleLock) {
                enabled = false;
                shutdownStarted = true;
            }
            platform.abortStartup();
            throw failure;
        }
    }

    @Override
    public void onDisable() {
        List<MoonBridgePermissionSubject> activeSubjects;
        List<ApiUserLeaseTracker.Lease<net.luckperms.api.model.user.User>> activeLeases;
        synchronized (lifecycleLock) {
            if (shutdownStarted) return;
            shutdownStarted = true;
            enabled = false;
            activeSubjects = List.copyOf(subjects.values());
            activeLeases = List.copyOf(sessionLeases.values());
            subjects.clear();
            sessionLeases.clear();
            latestConnection.clear();
        }
        try {
            for (MoonBridgePermissionSubject subject : activeSubjects) subject.close();
            for (ApiUserLeaseTracker.Lease<net.luckperms.api.model.user.User> lease : activeLeases) lease.close();
            if (platform != null) {
                if (engineEnabled) {
                    platform.prepareForDisable();
                    platform.disable();
                } else {
                    platform.abortStartup();
                }
            }
        } catch (RuntimeException | Error failure) {
            if (platform != null) platform.abortStartup();
            throw failure;
        } finally {
            if (bootstrap != null) bootstrap.close();
        }
    }

    @Override
    public Optional<PermissionProvider> permissionProvider() {
        return Optional.of(this);
    }

    @Override
    public CompletionStage<PermissionSubject> open(PlayerView view) {
        PlayerIdentity identity = view.identity();
        UUID uuid = identity.playerId();
        ApiUserLeaseTracker.Lease<net.luckperms.api.model.user.User> lease;
        ApiUserLeaseTracker.Lease<net.luckperms.api.model.user.User> staleLease = null;
        MoonBridgePermissionSubject staleSubject = null;
        synchronized (lifecycleLock) {
            if (!enabled) return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms is not enabled"));
            if (sessionLeases.containsKey(identity)) {
                return CompletableFuture.failedFuture(new IllegalStateException("Permission session is already opening"));
            }
            lease = userLeases.acquire(uuid);
            sessionLeases.put(identity, lease);
            PlayerIdentity previous = latestConnection.put(uuid, identity);
            if (previous != null && !previous.equals(identity)) {
                staleSubject = subjects.remove(uuid);
                staleLease = sessionLeases.remove(previous);
            }
        }

        try {
            if (staleSubject != null) staleSubject.close();
            if (staleLease != null) staleLease.close();
            LuckPerms api = platform.getApiProvider();
            return api.getUserManager().savePlayerData(uuid, view.username())
                    .thenApply(result -> {
                        if (result.includes(PlayerSaveResult.Outcome.CLEAN_INSERT)) {
                            platform.dispatchUserFirstLogin(uuid, view.username());
                        }
                        return result;
                    })
                    .thenCompose(ignored -> api.getUserManager().loadUser(uuid, view.username()))
                    .thenApply(apiUser -> {
                        lease.loaded(apiUser);
                        User user = ApiUser.cast(apiUser);
                        if (!isCurrent(identity)) {
                            throw new IllegalStateException("Player connection ended before LuckPerms loaded its user");
                        }
                        MoonBridgePermissionSubject subject = new MoonBridgePermissionSubject(this, view, user, lease);
                        platform.getContextManager().signalContextUpdate(subject.player());
                        synchronized (lifecycleLock) {
                            if (!enabled || !identity.equals(latestConnection.get(uuid))) {
                                throw new IllegalStateException("Player connection ended while LuckPerms loaded its user");
                            }
                            subjects.put(uuid, subject);
                        }
                        return (PermissionSubject) subject;
                    }).whenComplete((subject, failure) -> {
                    if (failure != null) {
                        lease.failed();
                        synchronized (lifecycleLock) {
                            sessionLeases.remove(identity, lease);
                            latestConnection.remove(uuid, identity);
                        }
                        lease.close();
                    }
                });
        } catch (Throwable failure) {
            lease.failed();
            synchronized (lifecycleLock) {
                sessionLeases.remove(identity, lease);
                latestConnection.remove(uuid, identity);
            }
            lease.close();
            return CompletableFuture.failedFuture(failure);
        }
    }

    void release(MoonBridgePermissionSubject subject) {
        synchronized (lifecycleLock) {
            subjects.remove(subject.identity().playerId(), subject);
            latestConnection.remove(subject.identity().playerId(), subject.identity());
            sessionLeases.remove(subject.identity(), subject.lease());
        }
        subject.lease().close();
    }

    PluginContext context() { return context; }
    MoonBridgeContextManager contextManager() { return platform.getContextManager(); }
    boolean isCurrent(PlayerIdentity identity) {
        synchronized (lifecycleLock) {
            return enabled && identity.equals(latestConnection.get(identity.playerId()));
        }
    }
    Optional<MoonBridgePermissionSubject> subject(UUID id) {
        synchronized (lifecycleLock) {
            MoonBridgePermissionSubject subject = subjects.get(id);
            return subject != null && subject.identity().equals(latestConnection.get(id))
                    ? Optional.of(subject) : Optional.empty();
        }
    }
}

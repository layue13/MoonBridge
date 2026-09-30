package dev.moonbridge.bukkit;

import dev.moonbridge.messaging.session.ForwardedSessionProof;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Validates legacy-forwarded profile proofs and binds them to real Bukkit player instances. */
final class OwnedBukkitSessionService implements BukkitSessionService, Listener, AutoCloseable {
    private static final int MAX_NONCES = 8192;
    private static final int MAX_PROVISIONAL_SESSIONS = 1024;
    private final String backendName;
    private final byte[] secret;
    private final LongSupplier backendEpoch;
    private final Supplier<UUID> proxyEpoch;
    private final LongSupplier clockMillis;
    private final Map<UUID, BoundSession> sessions = new ConcurrentHashMap<>();
    // Provisional bindings must not keep an aborted login's Player object alive.
    // All access is guarded by lifecycleLock and each entry has the proof expiry.
    private final Map<UUID, ProvisionalSession> provisionalSessions = new HashMap<>();
    private final Map<UUID, Long> consumedNonces = new HashMap<>();
    private final Object lifecycleLock = new Object();
    private volatile boolean closed;

    OwnedBukkitSessionService(String backendName, byte[] secret, LongSupplier backendEpoch,
                              Supplier<UUID> proxyEpoch) {
        this(backendName, secret, backendEpoch, proxyEpoch, System::currentTimeMillis);
    }

    OwnedBukkitSessionService(String backendName, byte[] secret, LongSupplier backendEpoch,
                              Supplier<UUID> proxyEpoch, LongSupplier clockMillis) {
        if (backendName == null || backendName.trim().isEmpty()) throw new IllegalArgumentException("backendName is required");
        if (secret == null || secret.length < 32) throw new IllegalArgumentException("session secret is too short");
        this.backendName = backendName;
        this.secret = secret.clone();
        this.backendEpoch = java.util.Objects.requireNonNull(backendEpoch, "backendEpoch");
        this.proxyEpoch = java.util.Objects.requireNonNull(proxyEpoch, "proxyEpoch");
        this.clockMillis = java.util.Objects.requireNonNull(clockMillis, "clockMillis");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        // The proof is transport metadata, not a game-profile property. Uranium
        // broadcasts every profile property when it spawns this player; its
        // legacy packet writer expects every property signature to be non-null.
        // Remove the private property before any normal-priority join handler
        // or the playerLoggedIn spawn broadcast can observe the profile.
        AuthenticationResult result = authenticateInternal(player, true);
        if (result.propertyRemovalFailed) {
            // Fail closed: do not bind a session when the forwarding credential
            // could not be removed from the profile before it is broadcast.
            if (result.foundProperty) player.kickPlayer("Unable to validate forwarded session");
        }
    }

    /** Removes only the provisional binding belonging to the denied login object. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (event == null || event.getResult() == PlayerLoginEvent.Result.ALLOWED) return;
        Player player = event.getPlayer();
        if (player == null) return;
        synchronized (lifecycleLock) {
            UUID playerId = player.getUniqueId();
            ProvisionalSession pending = provisionalSessions.get(playerId);
            if (pending != null && pending.player.get() == player)
                provisionalSessions.remove(playerId, pending);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        synchronized (lifecycleLock) {
            UUID playerId = player.getUniqueId();
            ProvisionalSession pending = provisionalSessions.get(playerId);
            if (pending != null && pending.player.get() == player)
                provisionalSessions.remove(playerId, pending);
            BoundSession current = sessions.get(playerId);
            if (current != null && current.player == player) sessions.remove(playerId, current);
        }
    }

    @Override
    public Optional<BackendPlayerSession> authenticate(Player player) {
        return Optional.ofNullable(authenticateInternal(player, false).session);
    }

    /** All proof stripping, nonce consumption and provisional promotion is serialized. */
    private AuthenticationResult authenticateInternal(Player player, boolean promote) {
        if (player == null) return AuthenticationResult.EMPTY;
        synchronized (lifecycleLock) {
            if (closed) return AuthenticationResult.EMPTY;
            UUID playerId = player.getUniqueId();
            if (playerId == null) return AuthenticationResult.EMPTY;
            long now = clockMillis.getAsLong();
            pruneProvisional(now);

            BoundSession active = sessions.get(playerId);
            if (active != null && !hasCurrentBackendIdentity(active.session)) {
                sessions.remove(playerId, active);
                if (active.player == player) return AuthenticationResult.EMPTY;
                active = null;
            }
            if (active != null && active.player == player) return AuthenticationResult.bound(active.session);

            ProvisionalSession pending = provisionalSessions.get(playerId);
            if (pending != null && !hasCurrentBackendIdentity(pending.session)) {
                provisionalSessions.remove(playerId, pending);
                if (pending.player.get() == player) return AuthenticationResult.EMPTY;
                pending = null;
            }
            if (pending != null && pending.player.get() == player && pending.expiresAt > now) {
                if (promote) {
                    provisionalSessions.remove(playerId, pending);
                    sessions.put(playerId, new BoundSession(player, pending.session));
                }
                return AuthenticationResult.bound(pending.session);
            }

            ProofProperty stripped = stripForwardedProof(player);
            if (!stripped.removed) return AuthenticationResult.removalFailure(stripped.foundProperty);

            // A UUID collision from a different live login must never replace the current owner.
            if (active != null && active.player != player) {
                if (active.player.isOnline()) return AuthenticationResult.EMPTY;
                sessions.remove(playerId, active);
            }
            if (pending != null) return AuthenticationResult.EMPTY;

            ForwardedSessionProof.Claims claims = ForwardedSessionProof.verify(
                    stripped.proof, secret, backendName, proxyEpoch.get(), backendEpoch.getAsLong(),
                    playerId, now).orElse(null);
            if (claims == null) return AuthenticationResult.EMPTY;
            if (provisionalSessions.size() >= MAX_PROVISIONAL_SESSIONS) return AuthenticationResult.EMPTY;
            if (!consumeNonce(claims.nonce(), claims.expiresAtEpochMillis(), now)) return AuthenticationResult.EMPTY;

            BackendPlayerSession session = new BackendPlayerSession(claims.playerId(), claims.proxyEpoch(),
                    claims.connectionId(), claims.backendName(), claims.backendEpoch(), UUID.randomUUID());
            ProvisionalSession created = new ProvisionalSession(player, session, claims.expiresAtEpochMillis());
            provisionalSessions.put(playerId, created);
            if (promote) {
                provisionalSessions.remove(playerId, created);
                sessions.put(playerId, new BoundSession(player, session));
            }
            return AuthenticationResult.bound(session);
        }
    }

    private void pruneProvisional(long now) {
        provisionalSessions.entrySet().removeIf(entry -> {
            ProvisionalSession pending = entry.getValue();
            return pending.expiresAt <= now || pending.player.get() == null;
        });
        consumedNonces.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private boolean hasCurrentBackendIdentity(BackendPlayerSession session) {
        UUID currentProxyEpoch = proxyEpoch.get();
        return currentProxyEpoch != null
                && currentProxyEpoch.equals(session.getProxyEpoch())
                && backendName.equals(session.getBackendName())
                && backendEpoch.getAsLong() == session.getBackendEpoch();
    }

    @Override
    public Optional<BackendPlayerSession> find(Player player) {
        if (player == null) return Optional.empty();
        synchronized (lifecycleLock) {
            if (closed || !player.isOnline()) return Optional.empty();
            UUID playerId = player.getUniqueId();
            BoundSession current = sessions.get(playerId);
            if (current == null || current.player != player) return Optional.empty();
            if (!hasCurrentBackendIdentity(current.session)) {
                sessions.remove(playerId, current);
                return Optional.empty();
            }
            return Optional.of(current.session);
        }
    }

    private boolean consumeNonce(UUID nonce, long expiresAt, long now) {
        if (closed) return false;
        if (consumedNonces.containsKey(nonce) || consumedNonces.size() >= MAX_NONCES) return false;
        consumedNonces.put(nonce, expiresAt);
        return true;
    }

    static ProofProperty stripForwardedProof(Player player) {
        Object profile = invokeNoArg(player, "getProfile");
        if (profile == null) profile = invokeNoArg(player, "getGameProfile");
        if (profile == null) return ProofProperty.unavailable();
        Object properties = invokeNoArg(profile, "getProperties");
        if (properties == null) return ProofProperty.unavailable();
        Object matches = invoke(properties, "get", new Class<?>[]{Object.class}, ForwardedSessionProof.PROPERTY_NAME);
        if (!(matches instanceof Iterable<?>)) return ProofProperty.unavailable();
        Collection<Object> propertiesToRemove = new java.util.ArrayList<Object>();
        Collection<String> values = new java.util.ArrayList<>(2);
        boolean allValuesValid = true;
        for (Object property : (Iterable<?>) matches) {
            if (property == null) return ProofProperty.failed(true);
            propertiesToRemove.add(property);
            Object value = invokeNoArg(property, "getValue");
            if (value instanceof String && ((String) value).length() <= 2048) values.add((String) value);
            else allValuesValid = false;
        }
        for (Object property : propertiesToRemove) {
            Object removed = invoke(properties, "remove", new Class<?>[]{Object.class, Object.class},
                    ForwardedSessionProof.PROPERTY_NAME, property);
            if (!Boolean.TRUE.equals(removed)) return ProofProperty.failed(true);
        }
        Object remaining = invoke(properties, "get", new Class<?>[]{Object.class},
                ForwardedSessionProof.PROPERTY_NAME);
        if (!(remaining instanceof Iterable<?>) || ((Iterable<?>) remaining).iterator().hasNext())
            return ProofProperty.failed(!propertiesToRemove.isEmpty());
        return ProofProperty.removed(propertiesToRemove.size() == 1 && allValuesValid && values.size() == 1
                ? values.iterator().next() : null);
    }

    private static Object invokeNoArg(Object receiver, String methodName) {
        if (receiver == null) return null;
        try {
            Method method = receiver.getClass().getMethod(methodName);
            return method.invoke(receiver);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private static Object invoke(Object receiver, String methodName, Class<?>[] parameterTypes, Object... args) {
        if (receiver == null) return null;
        try {
            Method method = receiver.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(receiver, args);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    static final class ProofProperty {
        final String proof;
        final boolean removed;
        final boolean foundProperty;

        private ProofProperty(String proof, boolean removed, boolean foundProperty) {
            this.proof = proof;
            this.removed = removed;
            this.foundProperty = foundProperty;
        }

        private static ProofProperty unavailable() { return new ProofProperty(null, false, false); }
        private static ProofProperty failed(boolean found) { return new ProofProperty(null, false, found); }
        private static ProofProperty removed(String proof) { return new ProofProperty(proof, true, proof != null); }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            sessions.clear();
            provisionalSessions.clear();
            consumedNonces.clear();
        }
        java.util.Arrays.fill(secret, (byte) 0);
    }

    private static final class BoundSession {
        private final Player player;
        private final BackendPlayerSession session;

        private BoundSession(Player player, BackendPlayerSession session) {
            this.player = player;
            this.session = session;
        }
    }

    private static final class ProvisionalSession {
        private final WeakReference<Player> player;
        private final BackendPlayerSession session;
        private final long expiresAt;

        private ProvisionalSession(Player player, BackendPlayerSession session, long expiresAt) {
            this.player = new WeakReference<>(player);
            this.session = session;
            this.expiresAt = expiresAt;
        }
    }

    private static final class AuthenticationResult {
        private static final AuthenticationResult EMPTY = new AuthenticationResult(null, false, false);
        private final BackendPlayerSession session;
        private final boolean propertyRemovalFailed;
        private final boolean foundProperty;

        private AuthenticationResult(BackendPlayerSession session, boolean propertyRemovalFailed,
                                     boolean foundProperty) {
            this.session = session;
            this.propertyRemovalFailed = propertyRemovalFailed;
            this.foundProperty = foundProperty;
        }

        private static AuthenticationResult bound(BackendPlayerSession session) {
            return new AuthenticationResult(session, false, false);
        }
        private static AuthenticationResult removalFailure(boolean foundProperty) {
            return new AuthenticationResult(null, true, foundProperty);
        }
    }
}

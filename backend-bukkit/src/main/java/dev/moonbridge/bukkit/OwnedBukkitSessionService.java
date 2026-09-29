package dev.moonbridge.bukkit;

import dev.moonbridge.messaging.session.ForwardedSessionProof;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Validates legacy-forwarded profile proofs and binds them to real Bukkit player instances. */
final class OwnedBukkitSessionService implements BukkitSessionService, Listener, AutoCloseable {
    private static final int MAX_NONCES = 8192;
    private final String backendName;
    private final byte[] secret;
    private final LongSupplier backendEpoch;
    private final Supplier<UUID> proxyEpoch;
    private final Map<UUID, BoundSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> consumedNonces = new HashMap<>();
    private final Map<String, PreverifiedSession> preverified = new HashMap<>();
    private volatile boolean closed;

    OwnedBukkitSessionService(String backendName, byte[] secret, LongSupplier backendEpoch,
                              Supplier<UUID> proxyEpoch) {
        if (backendName == null || backendName.trim().isEmpty()) throw new IllegalArgumentException("backendName is required");
        if (secret == null || secret.length < 32) throw new IllegalArgumentException("session secret is too short");
        this.backendName = backendName;
        this.secret = secret.clone();
        this.backendEpoch = java.util.Objects.requireNonNull(backendEpoch, "backendEpoch");
        this.proxyEpoch = java.util.Objects.requireNonNull(proxyEpoch, "proxyEpoch");
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
        BoundSession previous = sessions.get(player.getUniqueId());
        if (closed) return;
        ProofProperty stripped = stripForwardedProof(player);
        if (!stripped.removed) {
            // Fail closed: do not bind a session when the forwarding credential
            // could not be removed from the profile before it is broadcast.
            if (stripped.foundProperty) player.kickPlayer("Unable to validate forwarded session");
            return;
        }
        // Uranium redacts the transport proof before constructing the player.
        // Admission bound the exact preflight result to this player instance.
        if (previous != null && previous.player == player && stripped.proof == null) return;
        if (previous != null && previous.player == player) sessions.remove(player.getUniqueId(), previous);
        long now = System.currentTimeMillis();
        UUID expectedProxyEpoch = proxyEpoch.get();
        long expectedBackendEpoch = backendEpoch.getAsLong();
        BackendPlayerSession session = takePreverified(stripped.proof, player.getUniqueId(),
                expectedProxyEpoch, expectedBackendEpoch, now);
        if (session == null) {
            ForwardedSessionProof.Claims claims = ForwardedSessionProof.verify(
                    stripped.proof, secret, backendName, expectedProxyEpoch, expectedBackendEpoch,
                    player.getUniqueId(), now).orElse(null);
            if (claims == null || !consumeNonce(claims.nonce(), claims.expiresAtEpochMillis(), now)) return;
            session = new BackendPlayerSession(claims.playerId(), claims.proxyEpoch(),
                    claims.connectionId(), claims.backendName(), claims.backendEpoch(), UUID.randomUUID());
        }
        sessions.put(player.getUniqueId(), new BoundSession(player, session));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        BoundSession current = sessions.get(player.getUniqueId());
        if (current != null && current.player == player) sessions.remove(player.getUniqueId(), current);
    }

    @Override
    public Optional<BackendPlayerSession> find(Player player) {
        if (closed || player == null || !player.isOnline()) return Optional.empty();
        BoundSession current = sessions.get(player.getUniqueId());
        if (current == null || current.player != player) return Optional.empty();
        return Optional.of(current.session);
    }

    @Override
    public Optional<BackendPlayerSession> preflight(String forwardedProofToken, UUID expectedPlayerId) {
        if (closed || forwardedProofToken == null || expectedPlayerId == null) return Optional.empty();
        long now = System.currentTimeMillis();
        UUID expectedProxyEpoch = proxyEpoch.get();
        long expectedBackendEpoch = backendEpoch.getAsLong();
        ForwardedSessionProof.Claims claims = ForwardedSessionProof.verify(forwardedProofToken, secret,
                backendName, expectedProxyEpoch, expectedBackendEpoch, expectedPlayerId, now).orElse(null);
        if (claims == null) return Optional.empty();
        synchronized (this) {
            if (closed || !expectedProxyEpoch.equals(proxyEpoch.get())
                    || expectedBackendEpoch != backendEpoch.getAsLong()) return Optional.empty();
            pruneExpired(now);
            if (consumedNonces.containsKey(claims.nonce()) || consumedNonces.size() >= MAX_NONCES
                    || preverified.size() >= MAX_NONCES) return Optional.empty();
            consumedNonces.put(claims.nonce(), claims.expiresAtEpochMillis());
            BackendPlayerSession session = new BackendPlayerSession(claims.playerId(), claims.proxyEpoch(),
                    claims.connectionId(), claims.backendName(), claims.backendEpoch(), UUID.randomUUID());
            preverified.put(forwardedProofToken, new PreverifiedSession(session, claims.expiresAtEpochMillis()));
            return Optional.of(session);
        }
    }

    @Override
    public synchronized boolean bindPreverified(Player player, BackendPlayerSession session) {
        if (closed || player == null || session == null || !player.getUniqueId().equals(session.getPlayerId())) return false;
        ProofProperty redacted = stripForwardedProof(player);
        if (!redacted.removed || redacted.proof != null) return false;
        long now = System.currentTimeMillis();
        pruneExpired(now);
        if (!session.getProxyEpoch().equals(proxyEpoch.get()) || session.getBackendEpoch() != backendEpoch.getAsLong()
                || !session.getBackendName().equals(backendName)) return false;
        String matched = null;
        for (Map.Entry<String, PreverifiedSession> entry : preverified.entrySet()) {
            if (entry.getValue().session == session && entry.getValue().expiresAt > now) {
                matched = entry.getKey();
                break;
            }
        }
        if (matched == null) return false;
        preverified.remove(matched);
        BoundSession prior = sessions.putIfAbsent(player.getUniqueId(), new BoundSession(player, session));
        return prior == null;
    }

    @Override
    public void unbindPreverified(Player player, BackendPlayerSession session) {
        if (player == null || session == null) return;
        BoundSession current = sessions.get(player.getUniqueId());
        if (current != null && current.player == player && current.session == session)
            sessions.remove(player.getUniqueId(), current);
    }

    private synchronized BackendPlayerSession takePreverified(String token, UUID expectedPlayerId,
                                                               UUID expectedProxyEpoch, long expectedBackendEpoch,
                                                               long now) {
        pruneExpired(now);
        PreverifiedSession result = preverified.remove(token);
        if (result == null || result.expiresAt <= now) return null;
        BackendPlayerSession session = result.session;
        if (!expectedProxyEpoch.equals(proxyEpoch.get()) || expectedBackendEpoch != backendEpoch.getAsLong()
                || !session.getPlayerId().equals(expectedPlayerId)
                || !session.getProxyEpoch().equals(expectedProxyEpoch)
                || session.getBackendEpoch() != expectedBackendEpoch
                || !session.getBackendName().equals(backendName)) return null;
        return session;
    }

    private void pruneExpired(long now) {
        consumedNonces.entrySet().removeIf(entry -> entry.getValue() <= now);
        preverified.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
    }

    private synchronized boolean consumeNonce(UUID nonce, long expiresAt, long now) {
        if (closed) return false;
        pruneExpired(now);
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
        closed = true;
        sessions.clear();
        synchronized (this) { consumedNonces.clear(); preverified.clear(); }
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

    private static final class PreverifiedSession {
        private final BackendPlayerSession session;
        private final long expiresAt;

        private PreverifiedSession(BackendPlayerSession session, long expiresAt) {
            this.session = session;
            this.expiresAt = expiresAt;
        }
    }
}

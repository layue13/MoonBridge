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

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (closed) return;
        Player player = event.getPlayer();
        String proof = forwardedProof(player).orElse(null);
        if (proof == null) return;
        long now = System.currentTimeMillis();
        ForwardedSessionProof.Claims claims = ForwardedSessionProof.verify(
                proof, secret, backendName, proxyEpoch.get(), backendEpoch.getAsLong(),
                player.getUniqueId(), now).orElse(null);
        if (claims == null || !consumeNonce(claims.nonce(), claims.expiresAtEpochMillis(), now)) return;

        BackendPlayerSession session = new BackendPlayerSession(claims.playerId(), claims.proxyEpoch(),
                claims.connectionId(), claims.backendName(), claims.backendEpoch(), UUID.randomUUID());
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

    private synchronized boolean consumeNonce(UUID nonce, long expiresAt, long now) {
        if (closed) return false;
        consumedNonces.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (consumedNonces.containsKey(nonce) || consumedNonces.size() >= MAX_NONCES) return false;
        consumedNonces.put(nonce, expiresAt);
        return true;
    }

    private static Optional<String> forwardedProof(Player player) {
        Object profile = invokeNoArg(player, "getProfile");
        if (profile == null) profile = invokeNoArg(player, "getGameProfile");
        if (profile == null) return Optional.empty();
        Object properties = invokeNoArg(profile, "getProperties");
        if (properties == null) return Optional.empty();
        Object matches = invoke(properties, "get", new Class<?>[]{Object.class}, ForwardedSessionProof.PROPERTY_NAME);
        if (!(matches instanceof Iterable<?>)) return Optional.empty();
        Iterable<?> iterable = (Iterable<?>) matches;
        Collection<String> values = new java.util.ArrayList<>(2);
        for (Object property : iterable) {
            Object name = invokeNoArg(property, "getName");
            if (!ForwardedSessionProof.PROPERTY_NAME.equals(name)) continue;
            Object value = invokeNoArg(property, "getValue");
            if (value instanceof String && ((String) value).length() <= 2048) values.add((String) value);
            else return Optional.empty();
            if (values.size() > 1) return Optional.empty();
        }
        return values.size() == 1 ? Optional.of(values.iterator().next()) : Optional.empty();
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

    @Override
    public void close() {
        closed = true;
        sessions.clear();
        synchronized (this) { consumedNonces.clear(); }
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
}

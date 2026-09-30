package dev.moonbridge.bukkit;

import dev.moonbridge.messaging.session.ForwardedSessionProof;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OwnedBukkitSessionServiceTest {
    private static final String BACKEND = "island";
    private static final byte[] SECRET = new byte[32];
    private static final UUID PROXY_EPOCH = UUID.fromString("3b8f58d7-430b-40ca-8e59-c55598ee7f13");
    private static final long BACKEND_EPOCH = 7L;

    @Test void validProofIsConsumedBeforeBroadcastAndOtherProfilePropertiesSurvive() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        String proof = proof(playerId, nonce, System.currentTimeMillis() + 20_000L);
        TestProfile profile = new TestProfile(playerId, "player");
        TestProperty credential = new TestProperty(ForwardedSessionProof.PROPERTY_NAME, proof, null);
        TestProperty texture = new TestProperty("textures", "texture-value", "texture-signature");
        profile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME, credential);
        profile.getProperties().put("textures", texture);
        TestPlayer fixture = player(playerId, profile);
        OwnedBukkitSessionService service = service();

        service.onPlayerJoin(new PlayerJoinEvent(fixture.player, "joined"));

        assertTrue(profile.getProperties().get(ForwardedSessionProof.PROPERTY_NAME).isEmpty());
        assertSame(texture, profile.getProperties().get("textures").iterator().next());
        assertSame(profile, fixture.profile);
        BackendPlayerSession bound = service.find(fixture.player).orElseThrow(AssertionError::new);
        assertEquals(playerId, bound.getPlayerId());
        assertEquals(23L, bound.getConnectionId());
        assertFalse(fixture.kicked.get());
        assertEquals(EventPriority.LOWEST,
                OwnedBukkitSessionService.class.getMethod("onPlayerJoin", PlayerJoinEvent.class)
                        .getAnnotation(org.bukkit.event.EventHandler.class).priority());
        service.close();
    }

    @Test void invalidAndDuplicateProofsAreRemovedButNeverBindASession() {
        UUID playerId = UUID.randomUUID();
        TestProfile invalidProfile = new TestProfile(playerId, "invalid");
        invalidProfile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME,
                new TestProperty(ForwardedSessionProof.PROPERTY_NAME, "not-a-proof", null));
        TestPlayer invalidPlayer = player(playerId, invalidProfile);
        OwnedBukkitSessionService invalidService = service();

        invalidService.onPlayerJoin(new PlayerJoinEvent(invalidPlayer.player, "joined"));

        assertTrue(invalidProfile.getProperties().get(ForwardedSessionProof.PROPERTY_NAME).isEmpty());
        assertFalse(invalidService.find(invalidPlayer.player).isPresent());
        assertFalse(invalidPlayer.kicked.get());
        invalidService.close();

        TestProfile duplicateProfile = new TestProfile(playerId, "duplicate");
        String proof = proof(playerId, UUID.randomUUID(), System.currentTimeMillis() + 20_000L);
        duplicateProfile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME,
                new TestProperty(ForwardedSessionProof.PROPERTY_NAME, proof, null));
        duplicateProfile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME,
                new TestProperty(ForwardedSessionProof.PROPERTY_NAME, proof, null));
        TestPlayer duplicatePlayer = player(playerId, duplicateProfile);
        OwnedBukkitSessionService duplicateService = service();

        duplicateService.onPlayerJoin(new PlayerJoinEvent(duplicatePlayer.player, "joined"));

        assertTrue(duplicateProfile.getProperties().get(ForwardedSessionProof.PROPERTY_NAME).isEmpty());
        assertFalse(duplicateService.find(duplicatePlayer.player).isPresent());
        assertFalse(duplicatePlayer.kicked.get());
        duplicateService.close();
    }

    @Test void failedRemovalKicksAndDoesNotBindTheForwardedIdentity() {
        UUID playerId = UUID.randomUUID();
        String proof = proof(playerId, UUID.randomUUID(), System.currentTimeMillis() + 20_000L);
        NonRemovingProfile profile = new NonRemovingProfile(playerId, proof);
        TestPlayer fixture = player(playerId, profile);
        OwnedBukkitSessionService service = service();

        service.onPlayerJoin(new PlayerJoinEvent(fixture.player, "joined"));

        assertTrue(fixture.kicked.get());
        assertFalse(service.find(fixture.player).isPresent());
        service.close();
    }

    @Test void staleJoinWithoutProofCannotEraseAnotherPlayerInstancesBinding() {
        UUID playerId = UUID.randomUUID();
        TestProfile currentProfile = new TestProfile(playerId, "current");
        currentProfile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME,
                new TestProperty(ForwardedSessionProof.PROPERTY_NAME,
                        proof(playerId, UUID.randomUUID(), System.currentTimeMillis() + 20_000L), null));
        TestPlayer current = player(playerId, currentProfile);
        TestPlayer stale = player(playerId, new TestProfile(playerId, "stale"));
        OwnedBukkitSessionService service = service();

        service.onPlayerJoin(new PlayerJoinEvent(current.player, "joined"));
        BackendPlayerSession original = service.find(current.player).orElseThrow(AssertionError::new);
        service.onPlayerJoin(new PlayerJoinEvent(stale.player, "late stale join"));
        service.onPlayerQuit(new PlayerQuitEvent(stale.player, "stale quit"));

        assertSame(original, service.find(current.player).orElseThrow(AssertionError::new));
        assertFalse(service.find(stale.player).isPresent());
        service.close();
    }

    @Test void earlyAuthenticationIsIdempotentHiddenUntilJoinAndPromotesSameBinding() {
        UUID playerId = UUID.randomUUID();
        String proof = proof(playerId, UUID.randomUUID(), System.currentTimeMillis() + 20_000L);
        TestProfile profile = new TestProfile(playerId, "early");
        profile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME,
                new TestProperty(ForwardedSessionProof.PROPERTY_NAME, proof, null));
        TestPlayer fixture = player(playerId, profile, false);
        OwnedBukkitSessionService service = service();

        BackendPlayerSession first = service.authenticate(fixture.player).orElseThrow(AssertionError::new);
        BackendPlayerSession repeated = service.authenticate(fixture.player).orElseThrow(AssertionError::new);

        assertSame(first, repeated);
        assertFalse(service.find(fixture.player).isPresent(), "provisional binding must remain hidden from find");
        assertTrue(profile.getProperties().get(ForwardedSessionProof.PROPERTY_NAME).isEmpty());
        TestProfile replayProfile = new TestProfile(playerId, "replay");
        replayProfile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME,
                new TestProperty(ForwardedSessionProof.PROPERTY_NAME, proof, null));
        TestPlayer replay = player(playerId, replayProfile, false);
        assertFalse(service.authenticate(replay.player).isPresent(), "another player object cannot claim pending identity");
        fixture.online.set(true);
        service.onPlayerJoin(new PlayerJoinEvent(fixture.player, "joined"));

        assertSame(first, service.find(fixture.player).orElseThrow(AssertionError::new));
        service.close();
    }

    @Test void deniedLoginClearsOnlyItsProvisionalBinding() throws Exception {
        UUID playerId = UUID.randomUUID();
        TestPlayer denied = playerWithProof(playerId, System.currentTimeMillis() + 20_000L, false);
        OwnedBukkitSessionService service = service();
        BackendPlayerSession deniedSession = service.authenticate(denied.player).orElseThrow(AssertionError::new);

        PlayerLoginEvent deniedEvent = new PlayerLoginEvent(denied.player, "localhost",
                InetAddress.getLoopbackAddress());
        deniedEvent.setResult(PlayerLoginEvent.Result.KICK_OTHER);
        service.onPlayerLogin(deniedEvent);

        TestPlayer retry = playerWithProof(playerId, System.currentTimeMillis() + 20_000L, false);
        BackendPlayerSession retrySession = service.authenticate(retry.player).orElseThrow(AssertionError::new);
        assertNotSame(deniedSession, retrySession);
        assertFalse(service.find(denied.player).isPresent());
        service.close();
    }

    @Test void expiredProvisionalBindingDoesNotBlockFreshLoginForSameUuid() {
        UUID playerId = UUID.randomUUID();
        long start = 1_000_000L;
        AtomicLong clock = new AtomicLong(start);
        OwnedBukkitSessionService service = new OwnedBukkitSessionService(BACKEND, SECRET,
                () -> BACKEND_EPOCH, () -> PROXY_EPOCH, clock::get);
        TestPlayer expired = playerWithProof(playerId, start + 100L, false);
        BackendPlayerSession expiredSession = service.authenticate(expired.player).orElseThrow(AssertionError::new);

        clock.set(start + 101L);
        TestPlayer fresh = playerWithProof(playerId, start + 5_000L, false);
        BackendPlayerSession freshSession = service.authenticate(fresh.player).orElseThrow(AssertionError::new);

        assertNotSame(expiredSession, freshSession);
        assertFalse(service.find(expired.player).isPresent());
        service.close();
    }

    @Test void earlyBindingIsDiscardedWhenBackendEpochChangesBeforeJoin() {
        UUID playerId = UUID.randomUUID();
        AtomicLong currentBackendEpoch = new AtomicLong(BACKEND_EPOCH);
        AtomicReference<UUID> currentProxyEpoch = new AtomicReference<>(PROXY_EPOCH);
        OwnedBukkitSessionService service = new OwnedBukkitSessionService(BACKEND, SECRET,
                currentBackendEpoch::get, currentProxyEpoch::get);
        TestPlayer fixture = playerWithProof(playerId, System.currentTimeMillis() + 20_000L, false);
        assertTrue(service.authenticate(fixture.player).isPresent());

        currentBackendEpoch.incrementAndGet();
        fixture.online.set(true);
        service.onPlayerJoin(new PlayerJoinEvent(fixture.player, "joined after backend restart"));

        assertFalse(service.find(fixture.player).isPresent());
        assertFalse(service.authenticate(fixture.player).isPresent(), "stripped proof must not rebind implicitly");
        service.close();
    }

    @Test void findInvalidatesAnOnlineBindingWhenProxyEpochChanges() {
        UUID playerId = UUID.randomUUID();
        AtomicLong currentBackendEpoch = new AtomicLong(BACKEND_EPOCH);
        AtomicReference<UUID> currentProxyEpoch = new AtomicReference<>(PROXY_EPOCH);
        OwnedBukkitSessionService service = new OwnedBukkitSessionService(BACKEND, SECRET,
                currentBackendEpoch::get, currentProxyEpoch::get);
        TestPlayer fixture = playerWithProof(playerId, System.currentTimeMillis() + 20_000L, false);
        BackendPlayerSession authenticated = service.authenticate(fixture.player).orElseThrow(AssertionError::new);
        fixture.online.set(true);
        service.onPlayerJoin(new PlayerJoinEvent(fixture.player, "joined"));
        assertSame(authenticated, service.find(fixture.player).orElseThrow(AssertionError::new));

        currentProxyEpoch.set(UUID.randomUUID());

        assertFalse(service.find(fixture.player).isPresent());
        service.close();
    }

    private static OwnedBukkitSessionService service() {
        return new OwnedBukkitSessionService(BACKEND, SECRET, () -> BACKEND_EPOCH, () -> PROXY_EPOCH);
    }

    private static String proof(UUID playerId, UUID nonce, long expiry) {
        return ForwardedSessionProof.create(PROXY_EPOCH, playerId, 23L, BACKEND, BACKEND_EPOCH,
                nonce, expiry, SECRET);
    }

    private static TestPlayer player(UUID playerId, Object profile) {
        return player(playerId, profile, true);
    }

    private static TestPlayer playerWithProof(UUID playerId, long expiry, boolean online) {
        TestProfile profile = new TestProfile(playerId, "proof-player");
        profile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME,
                new TestProperty(ForwardedSessionProof.PROPERTY_NAME,
                        proof(playerId, UUID.randomUUID(), expiry), null));
        return player(playerId, profile, online);
    }

    private static TestPlayer player(UUID playerId, Object profile, boolean initiallyOnline) {
        AtomicBoolean kicked = new AtomicBoolean();
        AtomicBoolean online = new AtomicBoolean(initiallyOnline);
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class, ProfileHolder.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getUniqueId")) return playerId;
                    if (method.getName().equals("isOnline")) return online.get();
                    if (method.getName().equals("getGameProfile")) return profile;
                    if (method.getName().equals("kickPlayer")) { kicked.set(true); return null; }
                    if (method.getName().equals("toString")) return "test-player";
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == arguments[0];
                    return null;
                });
        return new TestPlayer(player, profile, kicked, online);
    }

    public interface ProfileHolder {
        Object getGameProfile();
    }

    public static final class NonRemovingProfile {
        private final UUID id;
        private final String proof;
        NonRemovingProfile(UUID id, String proof) { this.id = id; this.proof = proof; }
        public UUID getId() { return id; }
        public String getName() { return "non-removing"; }
        public Map<String, Collection<TestProperty>> getProperties() {
            Map<String, Collection<TestProperty>> properties = new HashMap<String, Collection<TestProperty>>();
            properties.put(ForwardedSessionProof.PROPERTY_NAME,
                    new NonRemovingCollection(new TestProperty(ForwardedSessionProof.PROPERTY_NAME, proof)));
            return properties;
        }
    }

    public static final class NonRemovingCollection extends ArrayList<TestProperty> {
        private static final long serialVersionUID = 1L;
        NonRemovingCollection(TestProperty property) { add(property); }
    }

    public static final class TestProperty {
        private final String name;
        private final String value;
        private final String signature;
        TestProperty(String name, String value) { this(name, value, null); }
        TestProperty(String name, String value, String signature) {
            this.name = name;
            this.value = value;
            this.signature = signature;
        }
        public String getName() { return name; }
        public String getValue() { return value; }
        public String getSignature() { return signature; }
    }

    public static final class TestProfile {
        private final UUID id;
        private final String name;
        private final TestPropertyMap properties = new TestPropertyMap();
        TestProfile(UUID id, String name) { this.id = id; this.name = name; }
        public UUID getId() { return id; }
        public String getName() { return name; }
        public TestPropertyMap getProperties() { return properties; }
    }

    public static final class TestPropertyMap {
        private final Map<String, Collection<TestProperty>> entries = new HashMap<String, Collection<TestProperty>>();
        public Collection<TestProperty> get(Object key) {
            Collection<TestProperty> values = entries.get(key);
            if (values == null) {
                values = new ArrayList<TestProperty>();
                entries.put((String) key, values);
            }
            return values;
        }
        public void put(String key, TestProperty property) { get(key).add(property); }
        public boolean remove(Object key, Object property) { return get(key).remove(property); }
    }

    private static final class TestPlayer {
        final Player player;
        final Object profile;
        final AtomicBoolean kicked;
        final AtomicBoolean online;
        TestPlayer(Player player, Object profile, AtomicBoolean kicked, AtomicBoolean online) {
            this.player = player;
            this.profile = profile;
            this.kicked = kicked;
            this.online = online;
        }
    }
}

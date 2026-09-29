package dev.moonbridge.bukkit;

import dev.moonbridge.messaging.session.ForwardedSessionProof;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test void preflightConsumesProofOnceAndJoinBindsTheSameVerifiedSession() {
        UUID playerId = UUID.randomUUID();
        String proof = proof(playerId, UUID.randomUUID(), System.currentTimeMillis() + 20_000L);
        OwnedBukkitSessionService service = service();

        BackendPlayerSession verified = service.preflight(proof, playerId).orElseThrow(AssertionError::new);

        assertEquals(playerId, verified.getPlayerId());
        assertEquals(PROXY_EPOCH, verified.getProxyEpoch());
        assertEquals(BACKEND_EPOCH, verified.getBackendEpoch());
        assertEquals(23L, verified.getConnectionId());
        assertFalse(service.preflight(proof, playerId).isPresent(), "a preflight proof is one-use");
        TestProfile profile = new TestProfile(playerId, "preflight");
        profile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME,
                new TestProperty(ForwardedSessionProof.PROPERTY_NAME, proof, null));
        TestPlayer fixture = player(playerId, profile);

        service.onPlayerJoin(new PlayerJoinEvent(fixture.player, "joined"));

        assertSame(verified, service.find(fixture.player).orElseThrow(AssertionError::new));
        assertTrue(profile.getProperties().get(ForwardedSessionProof.PROPERTY_NAME).isEmpty());
        assertFalse(fixture.kicked.get());
        service.close();
    }

    @Test void redactedPreflightBindsOnlyTheExactPlayerBeforeJoin() {
        UUID playerId = UUID.randomUUID();
        String proof = proof(playerId, UUID.randomUUID(), System.currentTimeMillis() + 20_000L);
        OwnedBukkitSessionService service = service();
        BackendPlayerSession verified = service.preflight(proof, playerId).orElseThrow(AssertionError::new);
        TestPlayer admitted = player(playerId, new TestProfile(playerId, "admitted"));
        TestPlayer other = player(playerId, new TestProfile(playerId, "other instance"));

        assertTrue(service.bindPreverified(admitted.player, verified));
        assertFalse(service.bindPreverified(other.player, verified), "preflight is one-use");
        assertSame(verified, service.find(admitted.player).orElseThrow(AssertionError::new));
        assertFalse(service.find(other.player).isPresent());
        service.onPlayerJoin(new PlayerJoinEvent(admitted.player, "joined"));
        assertSame(verified, service.find(admitted.player).orElseThrow(AssertionError::new));
        service.onPlayerJoin(new PlayerJoinEvent(other.player, "stale same-UUID join"));
        assertSame(verified, service.find(admitted.player).orElseThrow(AssertionError::new));
        assertFalse(service.find(other.player).isPresent());
        service.unbindPreverified(other.player, verified);
        assertSame(verified, service.find(admitted.player).orElseThrow(AssertionError::new));
        service.close();
    }

    @Test void preflightBindingRejectsWrongPlayerAndStaleProxyEpoch() {
        UUID playerId = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicReference<UUID> epoch =
                new java.util.concurrent.atomic.AtomicReference<>(PROXY_EPOCH);
        OwnedBukkitSessionService service = new OwnedBukkitSessionService(
                BACKEND, SECRET, () -> BACKEND_EPOCH, epoch::get);
        BackendPlayerSession verified = service.preflight(
                proof(playerId, UUID.randomUUID(), System.currentTimeMillis() + 20_000L), playerId)
                .orElseThrow(AssertionError::new);
        assertFalse(service.bindPreverified(player(UUID.randomUUID(), new TestProfile(UUID.randomUUID(), "wrong")).player,
                verified));
        epoch.set(UUID.randomUUID());
        TestPlayer admitted = player(playerId, new TestProfile(playerId, "stale"));
        assertFalse(service.bindPreverified(admitted.player, verified));
        assertFalse(service.find(admitted.player).isPresent());
        service.close();
    }

    @Test void preflightCacheIsRejectedAfterTheProxyEpochChanges() {
        UUID playerId = UUID.randomUUID();
        String proof = proof(playerId, UUID.randomUUID(), System.currentTimeMillis() + 20_000L);
        java.util.concurrent.atomic.AtomicReference<UUID> epoch =
                new java.util.concurrent.atomic.AtomicReference<>(PROXY_EPOCH);
        OwnedBukkitSessionService service = new OwnedBukkitSessionService(
                BACKEND, SECRET, () -> BACKEND_EPOCH, epoch::get);
        assertTrue(service.preflight(proof, playerId).isPresent());
        epoch.set(UUID.randomUUID());
        TestProfile profile = new TestProfile(playerId, "stale-preflight");
        profile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME,
                new TestProperty(ForwardedSessionProof.PROPERTY_NAME, proof, null));
        TestPlayer fixture = player(playerId, profile);

        service.onPlayerJoin(new PlayerJoinEvent(fixture.player, "joined"));

        assertFalse(service.find(fixture.player).isPresent());
        assertTrue(profile.getProperties().get(ForwardedSessionProof.PROPERTY_NAME).isEmpty());
        assertFalse(fixture.kicked.get());
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

        assertSame(original, service.find(current.player).orElseThrow(AssertionError::new));
        assertFalse(service.find(stale.player).isPresent());
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
        AtomicBoolean kicked = new AtomicBoolean();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class, ProfileHolder.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getUniqueId")) return playerId;
                    if (method.getName().equals("isOnline")) return true;
                    if (method.getName().equals("getGameProfile")) return profile;
                    if (method.getName().equals("kickPlayer")) { kicked.set(true); return null; }
                    if (method.getName().equals("toString")) return "test-player";
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == arguments[0];
                    return null;
                });
        return new TestPlayer(player, profile, kicked);
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
        TestPlayer(Player player, Object profile, AtomicBoolean kicked) {
            this.player = player;
            this.profile = profile;
            this.kicked = kicked;
        }
    }
}

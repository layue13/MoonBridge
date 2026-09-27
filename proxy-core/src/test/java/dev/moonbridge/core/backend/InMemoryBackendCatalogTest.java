package dev.moonbridge.core.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryBackendCatalogTest {
    private static final BackendId ID = new BackendId("lobby");

    @Test
    void backendRegistrationRejectsAnIgnoredUriPath() {
        assertThrows(IllegalArgumentException.class, () -> new BackendRegistration(ID,
                new BackendOwner("plugin:test", 1), URI.create("tcp://127.0.0.1:25565/world")));
    }

    @Test
    void registrationGenerationFencesOldHandlesAndSnapshotsRemainImmutable() {
        InMemoryBackendCatalog catalog = new InMemoryBackendCatalog();
        BackendOwner owner = new BackendOwner("plugin:test", 1);
        BackendView first = catalog.register(registration(owner));
        BackendView oldSnapshot = catalog.find(ID).orElseThrow();
        BackendRegistration changed = new BackendRegistration(ID, owner,
                URI.create("tcp://127.0.0.1:25566"), Map.of("stage", "game"), Map.of("region", "local"));
        BackendView updated = catalog.update(first.handle(), changed).orElseThrow();
        assertEquals(first.handle(), updated.handle());
        assertEquals(URI.create("tcp://127.0.0.1:25566"), updated.address());
        assertEquals(URI.create("tcp://127.0.0.1:25565"), oldSnapshot.address());
        assertThrows(UnsupportedOperationException.class, () -> oldSnapshot.tags().put("new", "value"));

        BackendView replacement = catalog.register(registration(owner));
        assertNotEquals(first.handle(), replacement.handle());
        assertTrue(catalog.update(first.handle(), changed).isEmpty());
        assertFalse(catalog.remove(first.handle()));
        assertEquals(URI.create("tcp://127.0.0.1:25565"), catalog.find(ID).orElseThrow().address());
        assertTrue(catalog.update(replacement.handle(), new BackendRegistration(ID,
                new BackendOwner("someone-else", 1), URI.create("tcp://127.0.0.1:25567"))).isEmpty());
    }

    @Test
    void ownerGenerationAndRemovalAreEnforced() {
        InMemoryBackendCatalog catalog = new InMemoryBackendCatalog();
        BackendOwner firstOwner = new BackendOwner("plugin:test", 2);
        BackendView first = catalog.register(registration(firstOwner));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.register(registration(new BackendOwner("plugin:test", 1))));
        assertThrows(IllegalStateException.class,
                () -> catalog.register(registration(new BackendOwner("other", 3))));
        assertEquals(0, catalog.removeOwner(new BackendOwner("plugin:test", 1)));
        assertEquals(1, catalog.removeOwner(firstOwner));
        assertTrue(catalog.find(ID).isEmpty());
        assertFalse(catalog.remove(first.handle()));
        BackendView replacement = catalog.register(registration(firstOwner));
        assertNotEquals(first.handle(), replacement.handle());
    }

    @Test
    void snapshotsKeepRegistrationOrder() {
        InMemoryBackendCatalog catalog = new InMemoryBackendCatalog();
        BackendOwner owner = new BackendOwner("static", 0);
        BackendView first = catalog.register(registration(owner));
        BackendView second = catalog.register(new BackendRegistration(new BackendId("game"), owner,
                URI.create("tcp://127.0.0.1:25566")));
        assertEquals(java.util.List.of(first.handle().id(), second.handle().id()),
                catalog.snapshot().stream().map(view -> view.handle().id()).toList());
        assertTrue(catalog.remove(first.handle()));
        assertEquals(java.util.List.of(second.handle().id()),
                catalog.snapshot().stream().map(view -> view.handle().id()).toList());
    }

    private static BackendRegistration registration(BackendOwner owner) {
        return new BackendRegistration(ID, owner, URI.create("tcp://127.0.0.1:25565"),
                Map.of("stage", "hub"), Map.of("region", "local"));
    }
}

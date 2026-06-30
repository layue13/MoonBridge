package dev.strataproxy.admin;

import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerCapability;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.registry.InMemoryServerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonRegistryStoreTest {
    @Test
    void savesAndLoadsServerDescriptors(@TempDir Path tempDir) throws Exception {
        var store = new JsonRegistryStore(tempDir.resolve("registry.json"));
        var registry = new InMemoryServerRegistry();
        registry.register(new ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25566),
                Set.of("survival"),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                new ProtocolRange(763, 763, "1.20.1"),
                100,
                180,
                220,
                false,
                Map.of("host", "survival.local")));

        store.save(registry.snapshot());
        var loaded = store.load();

        assertEquals(1, loaded.size());
        assertEquals("survival-1", loaded.getFirst().name());
        assertEquals(Set.of("survival"), loaded.getFirst().tags());
        assertEquals("survival.local", loaded.getFirst().metadata().get("host"));
    }

    @Test
    void adminServicePersistsRegisterAndUnregister(@TempDir Path tempDir) throws Exception {
        var path = tempDir.resolve("registry.json");
        var service = new AdminRegistryService(new InMemoryServerRegistry(), new JsonRegistryStore(path));

        service.register(new ServerDescriptor(
                "creative-1",
                new InetSocketAddress("127.0.0.1", 25567),
                Set.of("creative"),
                Set.of(ServerCapability.MODERN_FORWARDING),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                50,
                60,
                false,
                Map.of()));

        assertTrue(Files.readString(path).contains("creative-1"));
        service.unregister("creative-1", dev.strataproxy.api.server.DrainPolicy.rejectNew());
        assertTrue(new JsonRegistryStore(path).load().isEmpty());
    }

    @Test
    void persistsDrainMode(@TempDir Path tempDir) throws Exception {
        var store = new JsonRegistryStore(tempDir.resolve("registry.json"));
        var registry = new InMemoryServerRegistry();
        registry.register(new ServerDescriptor(
                "maintenance-1",
                new InetSocketAddress("127.0.0.1", 25568),
                Set.of("maintenance"),
                Set.of(ServerCapability.MODERN_FORWARDING),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                50,
                60,
                false,
                Map.of()));
        registry.updateDrainMode("maintenance-1", true);

        store.save(registry.snapshot());
        var loaded = store.load();

        assertEquals(1, loaded.size());
        assertTrue(loaded.getFirst().drainMode());
    }

    @Test
    void adminServiceRollsBackReplacementWhenPersistenceFails() {
        var registry = new InMemoryServerRegistry();
        registry.register(new ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("blue"),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                100,
                120,
                false,
                Map.of("host", "blue.local")));
        var service = new AdminRegistryService(registry, new FailingRegistryStore());

        assertThrows(IOException.class, () -> service.register(new ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25566),
                Set.of("green"),
                Set.of(ServerCapability.MODERN_FORWARDING),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                50,
                200,
                240,
                true,
                Map.of("host", "green.local"))));

        var restored = registry.get("survival-1").orElseThrow();
        assertEquals(25565, restored.descriptor().address().getPort());
        assertEquals(Set.of("blue"), restored.descriptor().tags());
        assertEquals(100, restored.load().softCapacity());
        assertEquals(120, restored.load().hardCapacity());
        assertTrue(!restored.draining());
    }

    private static final class FailingRegistryStore implements RegistryStore {
        @Override
        public List<ServerDescriptor> load() {
            return List.of();
        }

        @Override
        public void save(Collection<RegisteredServer> servers) throws IOException {
            throw new IOException("persist failed");
        }
    }
}

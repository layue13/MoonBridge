package dev.strataproxy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiValueTypesTest {
    @Test
    void serverDefinitionCopiesPluginCollections() {
        var tags = new HashMap<>(Map.of("region", "eu"));
        var metadata = new HashMap<>(Map.of("pool", "blue"));

        var definition = new ServerDefinition("game-1", URI.create("tcp://127.0.0.1:25565"),
                tags, 100, metadata);
        tags.put("region", "us");
        metadata.put("pool", "green");

        assertEquals(Map.of("region", "eu"), definition.tags());
        assertEquals(Map.of("pool", "blue"), definition.metadata());
        assertThrows(UnsupportedOperationException.class,
                () -> definition.tags().put("other", "value"));
    }

    @Test
    void serverDefinitionRequiresAnUnambiguousTcpEndpoint() {
        for (String address : new String[] {
                "tcp://operator@127.0.0.1:25565", "tcp://127.0.0.1:25565/world",
                "tcp://127.0.0.1:25565?mode=play", "tcp://127.0.0.1:25565#backend"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ServerDefinition("game-1", URI.create(address), Map.of(), 100, Map.of()), address);
        }
    }

    @Test
    void playerIdentityIncludesConnectionGeneration() {
        var id = UUID.randomUUID();

        assertTrue(!new PlayerIdentity(id, 1).equals(new PlayerIdentity(id, 2)));
        assertThrows(IllegalArgumentException.class, () -> new PlayerIdentity(id, -1));
    }

    @Test
    void placementRejectionBoundsTheClientVisibleReason() {
        assertEquals("Try again later", ((PlacementDecision.Reject) PlacementDecision.reject("Try again later")).reason());
        assertThrows(IllegalArgumentException.class, () -> PlacementDecision.reject(" "));
        assertThrows(IllegalArgumentException.class, () -> PlacementDecision.reject("x".repeat(1025)));
    }
}

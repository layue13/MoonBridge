package dev.moonbridge.api.permission;

import dev.moonbridge.api.PlayerIdentity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionApiTest {
    @Test
    void permissionContextDefensivelyCopiesMultivaluedValuesAndRetainsExplicitClears() {
        var values = new HashMap<String, Set<String>>();
        var backends = new HashSet<>(Set.of("lobby", "survival"));
        values.put("backend", backends);
        var context = new PermissionContext(values);

        values.clear();
        backends.clear();

        assertEquals(Set.of("lobby", "survival"), context.values().get("backend"));
        assertThrows(UnsupportedOperationException.class,
                () -> context.values().put("backend", Set.of("other")));
        assertThrows(UnsupportedOperationException.class,
                () -> context.values().get("backend").add("other"));
        assertEquals(Set.of(), new PermissionContext(Map.of("backend", Set.of())).values().get("backend"));
        assertEquals(Set.of("lobby", "survival", "minigame"),
                context.with("backend", "minigame").values().get("backend"));
    }

    @Test
    void booleanConvenienceOnlyGrantsAnExplicitAllow() {
        var identity = new PlayerIdentity(UUID.randomUUID(), 1);
        Permissions permissions = new Permissions() {
            @Override public PermissionResult check(PlayerIdentity player, String node) {
                return switch (node) {
                    case "allowed" -> PermissionResult.ALLOW;
                    case "denied" -> PermissionResult.DENY;
                    case "unset" -> PermissionResult.UNDEFINED;
                    default -> PermissionResult.UNAVAILABLE;
                };
            }

            @Override public PermissionResult check(PlayerIdentity player, String node, PermissionContext context) {
                return check(player, node);
            }
        };

        assertTrue(permissions.hasPermission(identity, "allowed"));
        assertFalse(permissions.hasPermission(identity, "denied"));
        assertFalse(permissions.hasPermission(identity, "unset"));
        assertFalse(permissions.hasPermission(identity, "unavailable"));
    }
}

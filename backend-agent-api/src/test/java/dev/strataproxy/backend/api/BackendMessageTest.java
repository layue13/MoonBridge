package dev.strataproxy.backend.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BackendMessageTest {
    @Test
    void copiesPayloadAtBothApiBoundaries() {
        byte[] source = new byte[] {1, 2, 3};
        BackendMessage message = new BackendMessage(new BackendPlayer(UUID.randomUUID(), "Steve"), "example:sync", source);
        source[0] = 9;
        byte[] delivered = message.payload();
        delivered[1] = 8;

        assertArrayEquals(new byte[] {1, 2, 3}, message.payload());
    }

    @Test
    void requiresAPlayerReferenceAndChannel() {
        assertThrows(IllegalArgumentException.class, () -> new BackendPlayer(null, " "));
        assertThrows(IllegalArgumentException.class, () -> new BackendMessage(
                new BackendPlayer(null, "Steve"), " ", new byte[0]));
    }

    @Test
    void reportsStableWriteOutcomes() {
        assertEquals("accepted_for_write", BackendMessageResult.acceptedForWrite().outcome());
        assertEquals("player_not_found", BackendMessageResult.failure("player_not_found").outcome());
    }
}

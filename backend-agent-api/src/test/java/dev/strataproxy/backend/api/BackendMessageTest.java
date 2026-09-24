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
        BackendMessage message = new BackendMessage(UUID.randomUUID().toString(), "", "survival-1", "example:sync", source);
        source[0] = 9;
        byte[] delivered = message.payload();
        delivered[1] = 8;

        assertArrayEquals(new byte[] {1, 2, 3}, message.payload());
    }

    @Test
    void requiresMessageIdAndChannel() {
        assertThrows(IllegalArgumentException.class, () -> new BackendMessage(
                UUID.randomUUID().toString(), "", "survival-1", " ", new byte[0]));
    }

    @Test
    void reportsStableWriteOutcomes() {
        assertEquals("accepted", BackendMessageResult.accepted("message-1").outcome());
        assertEquals("not_connected", BackendMessageResult.failure("not_connected").outcome());
    }
}

package dev.strataproxy.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BackendChannelsApiTest {
    @Test
    void inboundPayloadIsCopiedOnConstructionAndAccess() {
        byte[] input = {1, 2};
        var message = new BackendMessage("lobby", "instance-1", 1, "example:ping", input);

        input[0] = 9;
        byte[] firstRead = message.payload();
        firstRead[1] = 9;

        assertArrayEquals(new byte[] {1, 2}, message.payload());
    }

    @Test
    void inboundMessageRequiresAValidConnectionEpoch() {
        assertThrows(IllegalArgumentException.class,
                () -> new BackendMessage("lobby", "instance-1", 0, "example:ping", new byte[0]));
    }
}

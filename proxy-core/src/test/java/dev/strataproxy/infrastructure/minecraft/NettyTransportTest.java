package dev.strataproxy.infrastructure.minecraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class NettyTransportTest {
    @Test
    void selectsNioWhenNativeTransportIsDisabled() {
        var transport = NettyTransport.select(false, 1);
        try {
            assertEquals("nio", transport.name());
            assertFalse(transport.nativeTransport());
        } finally {
            transport.bossGroup().shutdownGracefully();
            transport.workerGroup().shutdownGracefully();
        }
    }
}

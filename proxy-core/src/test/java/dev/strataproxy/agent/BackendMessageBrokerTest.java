package dev.strataproxy.agent;

import dev.strataproxy.backend.api.DeliveryMode;
import dev.strataproxy.backend.internal.ChannelFrame;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BackendMessageBrokerTest {
    @Test
    void reliableMessagesRetryUntilAcknowledgedAndKeepCorrelation() {
        try (var broker = new BackendMessageBroker()) {
            var receiver = new RecordingSession();
            assertTrue(broker.attach("survival", "instance-1", receiver));
            broker.subscribe("survival", "instance-1", receiver, "test:events");
            var result = broker.forPlugin("example").publishTo("survival", "test:events",
                    "hello".getBytes(StandardCharsets.UTF_8), "request-7", DeliveryMode.RELIABLE).toCompletableFuture().join();
            assertTrue(result.accepted());
            assertFalse(result.messageId().isBlank());
            assertEquals(result.messageId(), receiver.frames.getFirst().messageId);
            assertEquals("request-7", receiver.frames.getFirst().correlationId);
            assertEquals("proxy:example", receiver.frames.getFirst().source);
            broker.retryPending();
            assertEquals(2, receiver.frames.size());
            broker.acknowledge("survival", "instance-1", receiver, result.messageId());
            broker.retryPending();
            assertEquals(2, receiver.frames.size());
        }
    }

    @Test
    void oldSessionCannotPublishOrAcknowledgeAfterReplacement() {
        try (var broker = new BackendMessageBroker()) {
            var old = new RecordingSession();
            var current = new RecordingSession();
            assertTrue(broker.attach("survival", "instance-1", old));
            broker.subscribe("survival", "instance-1", old, "test:events");
            assertTrue(broker.attach("survival", "instance-1", current));
            assertTrue(old.closed);
            broker.subscribe("survival", "instance-1", current, "test:events");
            var result = broker.forPlugin("example").publishTo("survival", "test:events", new byte[0], "", DeliveryMode.RELIABLE)
                    .toCompletableFuture().join();
            broker.acknowledge("survival", "instance-1", old, result.messageId());
            broker.retryPending();
            assertEquals(2, current.frames.size());
            var forged = new ChannelFrame(ChannelFrame.PUBLISH, "req", "", "", "", "test:events", "BEST_EFFORT", "", new byte[0]);
            assertEquals("not_connected", broker.publishFromBackend("survival", "instance-1", old, forged).outcome());
        }
    }

    @Test
    void backendAndProxyCanPublishWithoutPlayers() throws Exception {
        try (var broker = new BackendMessageBroker()) {
            var observed = new CountDownLatch(1);
            var messages = new ArrayList<String>();
            broker.forPlugin("example").subscribe("test:events", message -> {
                synchronized (messages) { messages.add(message.source() + ":" + message.messageId()); }
                observed.countDown();
            });
            var session = new RecordingSession();
            broker.attach("survival", "instance-1", session);
            var result = broker.publishFromBackend("survival", "instance-1", session,
                    new ChannelFrame(ChannelFrame.PUBLISH, "req", "", "", "", "test:events", "BEST_EFFORT", "", new byte[] {1}));
            assertTrue(result.accepted());
            assertTrue(observed.await(1, TimeUnit.SECONDS));
            synchronized (messages) { assertEquals("backend:survival:instance-1:" + result.messageId(), messages.getFirst()); }
            var other = broker.publishFromBackend("survival", "instance-1", session,
                    new ChannelFrame(ChannelFrame.PUBLISH, "req2", "", "", "", "test:events", "BEST_EFFORT", "", new byte[] {1}));
            assertNotEquals(result.messageId(), other.messageId());
        }
    }

    @Test
    void idempotencyKeyReturnsOriginalIdAndRejectsChangedPayload() {
        try (var broker = new BackendMessageBroker()) {
            var session = new RecordingSession();
            broker.attach("survival", "instance-1", session);
            broker.subscribe("survival", "instance-1", session, "test:events");
            var channels = broker.forPlugin("example");
            var first = channels.publishTo("survival", "test:events", new byte[] {1}, "corr", "operation-1", DeliveryMode.RELIABLE)
                    .toCompletableFuture().join();
            var repeated = channels.publishTo("survival", "test:events", new byte[] {1}, "corr", "operation-1", DeliveryMode.RELIABLE)
                    .toCompletableFuture().join();
            var conflict = channels.publishTo("survival", "test:events", new byte[] {2}, "corr", "operation-1", DeliveryMode.RELIABLE)
                    .toCompletableFuture().join();
            assertEquals(first.messageId(), repeated.messageId());
            assertEquals(1, session.frames.size());
            assertEquals("idempotency_conflict", conflict.outcome());
        }
    }

    private static final class RecordingSession implements BackendMessageBroker.Session {
        private final List<ChannelFrame> frames = new ArrayList<>();
        private boolean closed;
        @Override public boolean offer(ChannelFrame frame) { frames.add(frame); return true; }
        @Override public int remainingCapacity() { return 256; }
        @Override public void close() { closed = true; }
    }
}

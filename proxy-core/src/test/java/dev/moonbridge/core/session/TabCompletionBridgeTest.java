package dev.moonbridge.core.session;

import dev.moonbridge.core.protocol.Minecraft1710PlayPackets;
import dev.moonbridge.core.protocol.MinecraftTabCompletion;
import dev.moonbridge.core.protocol.ProtocolVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TabCompletionBridgeTest {
    private static ByteBuf request(String text) {
        ByteBuf body = Unpooled.buffer();
        body.writeByte(0x14);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ProtocolVarInt.write(body, bytes.length);
        body.writeBytes(bytes);
        try { return Minecraft1710PlayPackets.frame(UnpooledByteBufAllocatorHolder.ALLOC, body); }
        finally { body.release(); }
    }
    private static final class UnpooledByteBufAllocatorHolder {
        static final io.netty.buffer.ByteBufAllocator ALLOC = io.netty.buffer.UnpooledByteBufAllocator.DEFAULT;
    }
    private static List<String> read(EmbeddedChannel client) {
        ByteBuf frame = client.readOutbound();
        assertNotNull(frame);
        try { return MinecraftTabCompletion.response(frame); } finally { frame.release(); }
    }
    private static final class Fixture implements AutoCloseable {
        final EmbeddedChannel client = new EmbeddedChannel();
        final EmbeddedChannel backend = new EmbeddedChannel();
        final CompletableFuture<List<String>> result = new CompletableFuture<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final TabCompletionBridge bridge = new TabCompletionBridge(client,
                prefix -> List.of("/proxy"),
                text -> text.startsWith("/proxy ") ? Optional.of(result) : Optional.empty(),
                () -> { closed.set(true); });
        Fixture() {
            client.pipeline().addLast(bridge.frontendHandler());
            backend.pipeline().addLast(bridge.backendHandler());
        }
        void backendReply(List<String> values) {
            backend.writeInbound(MinecraftTabCompletion.response(backend.alloc(), values));
        }
        void releaseForwarded() { ByteBuf frame = client.readInbound(); assertNotNull(frame); frame.release(); }
        @Override public void close() { bridge.close(); client.finishAndReleaseAll(); backend.finishAndReleaseAll(); }
    }

    @Test void mergesRootNamesAndDeduplicates() {
        try (Fixture f = new Fixture()) {
            f.client.writeInbound(request("/p")); f.releaseForwarded();
            f.backendReply(List.of("/proxy", "/plugins"));
            assertEquals(List.of("/proxy", "/plugins"), read(f.client));
            assertFalse(f.closed.get());
        }
    }

    @Test void backendCannotReintroduceHiddenProxyRoots() {
        var client = new EmbeddedChannel();
        var backend = new EmbeddedChannel();
        try (var bridge = new TabCompletionBridge(client, prefix -> List.of("/public"),
                text -> Optional.empty(), () -> fail("unexpected disconnect"),
                suggestion -> !suggestion.equalsIgnoreCase("/private"))) {
            client.pipeline().addLast(bridge.frontendHandler());
            backend.pipeline().addLast(bridge.backendHandler());
            client.writeInbound(request("/p"));
            ByteBuf forwarded = client.readInbound();
            assertNotNull(forwarded);
            forwarded.release();
            backend.writeInbound(MinecraftTabCompletion.response(backend.alloc(),
                    List.of("/private", "/PRIVATE", "/public", "/plugins")));
            assertEquals(List.of("/public", "/plugins"), read(client));
        } finally {
            client.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    @Test void localArgumentsNeverReachBackendAndCompleteOffCallback() {
        try (Fixture f = new Fixture()) {
            f.client.writeInbound(request("/proxy "));
            assertNull(f.client.readInbound());
            f.result.complete(List.of("lobby", "islands"));
            f.client.runPendingTasks();
            assertEquals(List.of("lobby", "islands"), read(f.client));
        }
    }

    @Test void unknownCommandResponsePreservesExactFrame() {
        try (Fixture f = new Fixture()) {
            f.client.writeInbound(request("/backend arg")); f.releaseForwarded();
            ByteBuf original = MinecraftTabCompletion.response(f.backend.alloc(), List.of("argument"));
            ByteBuf expected = original.copy();
            f.backend.writeInbound(original);
            ByteBuf actual = f.client.readOutbound();
            try { assertEquals(expected, actual); } finally { expected.release(); actual.release(); }
        }
    }

    @Test void timedOutBackendReplyCannotCompleteNewLocalRequest() {
        try (Fixture f = new Fixture()) {
            f.client.writeInbound(request("/p")); f.releaseForwarded();
            f.client.advanceTimeBy(2, TimeUnit.SECONDS); f.client.runScheduledPendingTasks();
            assertEquals(List.of("/proxy"), read(f.client));
            f.client.writeInbound(request("/proxy "));
            f.backendReply(List.of("stale"));
            assertNull(f.client.readOutbound());
            f.result.complete(List.of("fresh")); f.client.runPendingTasks();
            assertEquals(List.of("fresh"), read(f.client));
        }
    }

    @Test void doesNotIssueAnotherBackendRequestUntilExpiredReplyIsConsumed() {
        try (Fixture f = new Fixture()) {
            f.client.writeInbound(request("/other arg")); f.releaseForwarded();
            f.client.advanceTimeBy(2, TimeUnit.SECONDS); f.client.runScheduledPendingTasks(); read(f.client);
            f.client.writeInbound(request("/other next"));
            assertNull(f.client.readInbound()); assertEquals(List.of(), read(f.client));
            f.backendReply(List.of("old")); assertNull(f.client.readOutbound());
            f.client.writeInbound(request("/other last")); f.releaseForwarded();
            f.backendReply(List.of("last")); assertEquals(List.of("last"), read(f.client));
        }
    }

    @Test void closesGenerationAndCancelsPluginWorkReleasingQueuedFrames() {
        try (Fixture f = new Fixture()) {
            f.client.writeInbound(request("/proxy "));
            ByteBuf queued = request("/p"); f.client.writeInbound(queued);
            f.bridge.close();
            assertTrue(f.result.isCancelled()); assertEquals(0, queued.refCnt());
            f.client.runPendingTasks(); assertNull(f.client.readOutbound());
        }
    }

    @Test void ordinaryFramesPassThroughByIdentity() {
        try (Fixture f = new Fixture()) {
            ByteBuf frame = Unpooled.wrappedBuffer(new byte[]{2, 1, 0});
            f.client.writeInbound(frame);
            assertSame(frame, f.client.readInbound()); frame.release();
        }
    }

    @Test void localTimeoutReturnsEmptyAndCancelsStage() {
        try (Fixture f = new Fixture()) {
            f.client.writeInbound(request("/proxy "));
            f.client.advanceTimeBy(2, TimeUnit.SECONDS); f.client.runScheduledPendingTasks();
            assertEquals(List.of(), read(f.client)); assertTrue(f.result.isCancelled());
        }
    }

    @Test void replacementGenerationIgnoresOldBackendReplies() {
        try (Fixture f = new Fixture()) {
            EmbeddedChannel replacement = new EmbeddedChannel();
            f.client.writeInbound(request("/p")); f.releaseForwarded();
            f.bridge.close();
            f.client.pipeline().removeFirst();
            var next = new TabCompletionBridge(f.client, prefix -> List.of("/new"),
                    text -> Optional.empty(), () -> f.closed.set(true));
            try {
                f.client.pipeline().addLast(next.frontendHandler());
                replacement.pipeline().addLast(next.backendHandler());
                f.client.writeInbound(request("/n")); f.releaseForwarded();
                f.backendReply(List.of("/old"));
                assertNull(f.client.readOutbound());
                replacement.writeInbound(MinecraftTabCompletion.response(replacement.alloc(), List.of("/native")));
                assertEquals(List.of("/new", "/native"), read(f.client));
            } finally { next.close(); replacement.finishAndReleaseAll(); }
        }
    }

    @Test void queuedRequestsStayInOrderAndOverflowClosesConnection() {
        try (Fixture f = new Fixture()) {
            f.client.writeInbound(request("/proxy "));
            for (int i = 0; i < 8; i++) f.client.writeInbound(request("/p"));
            ByteBuf overflow = request("/p"); f.client.writeInbound(overflow);
            assertTrue(f.closed.get()); assertEquals(0, overflow.refCnt());
        }
        try (Fixture f = new Fixture()) {
            f.client.writeInbound(request("/proxy "));
            f.client.writeInbound(request("/p"));
            assertNull(f.client.readInbound());
            f.result.complete(List.of("first")); f.client.runPendingTasks();
            assertEquals(List.of("first"), read(f.client));
            f.releaseForwarded();
            f.backendReply(List.of("/second"));
            assertEquals(List.of("/proxy", "/second"), read(f.client));
        }
    }

    @Test void requestArrivingAfterCloseIsReleased() {
        try (Fixture f = new Fixture()) {
            f.bridge.close();
            ByteBuf late = request("/p");
            f.client.writeInbound(late);
            assertEquals(0, late.refCnt());
            assertNull(f.client.readInbound());
        }
    }

    @Test void malformedRequestIsReleasedAndClosesTheSession() {
        try (Fixture f = new Fixture()) {
            ByteBuf body = Unpooled.buffer();
            body.writeByte(0x14);
            ProtocolVarInt.write(body, 5); // promises five bytes of text but carries one
            body.writeByte('a');
            ByteBuf frame;
            try { frame = Minecraft1710PlayPackets.frame(UnpooledByteBufAllocatorHolder.ALLOC, body); }
            finally { body.release(); }
            f.client.writeInbound(frame);
            assertEquals(0, frame.refCnt());
            assertTrue(f.closed.get());
        }
    }

    @Test void nonBufferMessagesAreReleasedAndCloseTheSession() {
        try (Fixture f = new Fixture()) {
            var fromClient = new io.netty.buffer.DefaultByteBufHolder(Unpooled.buffer().writeByte(1));
            f.client.writeInbound(fromClient);
            assertEquals(0, fromClient.refCnt());
            assertTrue(f.closed.get());
            f.closed.set(false);
            var fromBackend = new io.netty.buffer.DefaultByteBufHolder(Unpooled.buffer().writeByte(1));
            f.backend.writeInbound(fromBackend);
            assertEquals(0, fromBackend.refCnt());
            assertTrue(f.closed.get());
        }
    }
}

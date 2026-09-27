package dev.strataproxy.messaging.internal;

import dev.strataproxy.messaging.Endpoint;
import dev.strataproxy.messaging.Message;
import dev.strataproxy.messaging.MessageKind;
import dev.strataproxy.messaging.Messaging;
import dev.strataproxy.messaging.MessagingException;
import dev.strataproxy.messaging.PublishResult;
import dev.strataproxy.messaging.SendResult;
import dev.strataproxy.messaging.SendReceipt;
import dev.strataproxy.messaging.Subscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMessagingTest {
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    private final TestOutbound outbound = new TestOutbound();
    private final Endpoint self = Endpoint.backend("island-a");
    private final LocalMessaging host = new LocalMessaging(self, outbound, DIRECT, scheduler);

    private static final Executor DIRECT = new Executor() {
        @Override public void execute(Runnable command) { command.run(); }
    };

    @AfterEach
    void tearDown() {
        host.close();
        scheduler.shutdownNow();
    }

    @Test
    void messageDefensivelyCopiesPayloadAndReplyLinksAutomatically() {
        byte[] original = new byte[] {1, 2};
        Message request = Message.request("islands:prepare", Endpoint.proxy(), self, original);
        original[0] = 9;
        assertEquals(1, request.payload()[0]);
        byte[] read = request.payload();
        read[1] = 8;
        assertEquals(2, request.payload()[1]);

        Message reply = Message.reply(request, self, new byte[] {3});
        assertEquals(MessageKind.REPLY, reply.kind());
        assertEquals(request.id(), reply.replyTo());
        assertEquals(request.source(), reply.target());
        assertNotEquals(request.id(), reply.id());
    }

    @Test
    void eventListenersFanOutAndScopeCloseRemovesOnlyThatOwner() {
        Messaging first = host.openScope("plugin-one");
        Messaging second = host.openScope("plugin-two");
        AtomicInteger one = new AtomicInteger();
        AtomicInteger two = new AtomicInteger();
        Subscription firstSub = first.channel("islands:event").subscribe(message -> one.incrementAndGet());
        Subscription secondSub = second.channel("islands:event").subscribe(message -> two.incrementAndGet());

        assertEquals(Integer.valueOf(1), host.subscriptions().get("islands:event"));
        assertEquals(SendResult.ACCEPTED, host.receiveEvent(Message.event("islands:event", Endpoint.proxy(), self,
                new byte[] {1})));
        assertEquals(1, one.get());
        assertEquals(1, two.get());

        first.close();
        assertEquals(Integer.valueOf(1), host.subscriptions().get("islands:event"));
        assertEquals(SendResult.ACCEPTED, host.receiveEvent(Message.event("islands:event", Endpoint.proxy(), self,
                new byte[] {2})));
        assertEquals(1, one.get());
        assertEquals(2, two.get());

        secondSub.close();
        assertEquals(SendResult.NO_SUBSCRIBER, host.receiveEvent(Message.event("islands:event", Endpoint.proxy(), self,
                new byte[0])));
        firstSub.close();
    }

    @Test
    void requestHandlerIsExclusiveAndReplyIsGeneratedByTheHost() throws Exception {
        Messaging first = host.openScope("request-owner");
        Messaging second = host.openScope("duplicate-owner");
        first.channel("islands:query").onRequest(request ->
                CompletableFuture.completedFuture(new byte[] {request.payload()[0]}));

        MessagingException conflict = assertThrows(MessagingException.class,
                () -> second.channel("islands:query").onRequest(request ->
                        CompletableFuture.completedFuture(new byte[0])));
        assertEquals(MessagingException.Code.REJECTED, conflict.code());
        assertEquals(Integer.valueOf(2), host.subscriptions().get("islands:query"));

        Message request = Message.request("islands:query", Endpoint.proxy(), self, new byte[] {7});
        Message reply = host.receiveRequest(request, Duration.ofSeconds(1)).toCompletableFuture()
                .get(1, TimeUnit.SECONDS);
        assertEquals(request.id(), reply.replyTo());
        assertArrayEquals(new byte[] {7}, reply.payload());
        assertEquals(self, reply.source());
        assertEquals(Endpoint.proxy(), reply.target());
    }

    @Test
    void neverCompletingHandlerTimesOutAndReleasesItsInboundSlot() throws Exception {
        Messaging scope = host.openScope("slow-handler");
        scope.channel("islands:slow").onRequest(request -> new CompletableFuture<byte[]>());
        List<CompletionStage<Message>> pending = new ArrayList<CompletionStage<Message>>();
        for (int index = 0; index < LocalMessaging.MAX_IN_FLIGHT; index++) {
            pending.add(host.receiveRequest(request("islands:slow"), Duration.ofMillis(40)));
        }
        MessagingException saturated = failure(host.receiveRequest(request("islands:slow"), Duration.ofMillis(40)));
        assertEquals(MessagingException.Code.BACKPRESSURED, saturated.code());

        MessagingException timedOut = failure(pending.get(0));
        assertEquals(MessagingException.Code.TIMED_OUT, timedOut.code());
        assertNotNull(host.receiveRequest(request("islands:slow"), Duration.ofMillis(40)));
    }

    @Test
    void outboundRequestsRemainCorrelatedWhenRepliesArriveOutOfOrder() throws Exception {
        Messaging scope = host.openScope("caller");
        CompletionStage<Message> first = scope.channel("islands:rpc").request(Endpoint.proxy(), new byte[] {1},
                Duration.ofSeconds(1));
        CompletionStage<Message> second = scope.channel("islands:rpc").request(Endpoint.proxy(), new byte[] {2},
                Duration.ofSeconds(1));
        assertEquals(2, outbound.requests.size());
        Message firstRequest = outbound.requests.get(0);
        Message secondRequest = outbound.requests.get(1);
        assertNotEquals(firstRequest.id(), secondRequest.id());

        outbound.requestFutures.get(secondRequest.id()).complete(Message.reply(secondRequest, Endpoint.proxy(),
                new byte[] {22}));
        outbound.requestFutures.get(firstRequest.id()).complete(Message.reply(firstRequest, Endpoint.proxy(),
                new byte[] {11}));
        assertArrayEquals(new byte[] {11}, first.toCompletableFuture().get(1, TimeUnit.SECONDS).payload());
        assertArrayEquals(new byte[] {22}, second.toCompletableFuture().get(1, TimeUnit.SECONDS).payload());
    }

    @Test
    void outgoingRequestTimeoutIncludesAnUnresponsiveOutbound() throws Exception {
        Messaging scope = host.openScope("timeout-caller");
        CompletionStage<Message> pending = scope.channel("islands:timeout").request(Endpoint.proxy(), new byte[0],
                Duration.ofMillis(30));
        MessagingException timeout = failure(pending);
        assertEquals(MessagingException.Code.TIMED_OUT, timeout.code());
    }

    @Test
    void closingScopeReleasesEventsWhoseExecutorQueueWasDiscarded() {
        QueuedExecutor queued = new QueuedExecutor();
        Messaging cancelled = host.openScope("queued-listener", queued);
        cancelled.channel("islands:queued").subscribe(message -> { });
        for (int index = 0; index < LocalMessaging.MAX_IN_FLIGHT; index++) {
            assertEquals(SendResult.ACCEPTED, host.receiveEvent(Message.event("islands:queued", Endpoint.proxy(),
                    self, new byte[0])));
        }
        assertEquals(SendResult.BACKPRESSURED, host.receiveEvent(Message.event("islands:queued", Endpoint.proxy(),
                self, new byte[0])));

        queued.clear(); // Models an executor that discards queued work during plugin disable.
        cancelled.close();
        QueuedExecutor replacementQueue = new QueuedExecutor();
        Messaging replacement = host.openScope("replacement", replacementQueue);
        replacement.channel("islands:queued").subscribe(message -> { });
        assertEquals(SendResult.ACCEPTED, host.receiveEvent(Message.event("islands:queued", Endpoint.proxy(),
                self, new byte[0])));
        replacement.close();
    }

    @Test
    void closedOwnerExecutorCannotStrandPublicCompletion() throws Exception {
        QueuedExecutor ownerExecutor = new QueuedExecutor();
        Messaging scope = host.openScope("queued-owner", ownerExecutor);
        CompletionStage<Message> pending = scope.channel("islands:rpc").request(Endpoint.proxy(), new byte[0],
                Duration.ofSeconds(1));
        scope.close();
        MessagingException failure = failure(pending);
        assertEquals(MessagingException.Code.CLOSED, failure.code());
        assertTrue(ownerExecutor.size() == 0);
    }

    @Test
    void cancellingPublicFutureReleasesOutboundCapacity() {
        outbound.neverCompleteSends = true;
        Messaging scope = host.openScope("cancel-sends");
        List<CompletableFuture<?>> sends = new ArrayList<CompletableFuture<?>>();
        for (int index = 0; index < LocalMessaging.MAX_IN_FLIGHT; index++) {
            sends.add(scope.channel("islands:send").send(Endpoint.proxy(), new byte[0]).toCompletableFuture());
        }
        assertTrue(sends.get(0).cancel(true));
        assertTrue(outbound.sendFutures.get(0).isCancelled(),
                "cancelling the public send must cancel its underlying outbound stage");
        CompletableFuture<?> afterCancel = scope.channel("islands:send").send(Endpoint.proxy(), new byte[0])
                .toCompletableFuture();
        assertNotNull(afterCancel);
        afterCancel.cancel(true);
        scope.close();
    }

    @Test
    void sendMapsTypedTransportFailuresToReceipts() throws Exception {
        Messaging scope = host.openScope("send-statuses");
        Map<MessagingException.Code, SendResult> expected = new LinkedHashMap<>();
        expected.put(MessagingException.Code.TIMED_OUT, SendResult.TIMED_OUT);
        expected.put(MessagingException.Code.BACKPRESSURED, SendResult.BACKPRESSURED);
        expected.put(MessagingException.Code.NOT_CONNECTED, SendResult.NOT_CONNECTED);
        expected.put(MessagingException.Code.NO_HANDLER, SendResult.NO_SUBSCRIBER);
        expected.put(MessagingException.Code.REJECTED, SendResult.REJECTED);
        expected.put(MessagingException.Code.HANDLER_FAILED, SendResult.FAILED);
        expected.put(MessagingException.Code.PROTOCOL_ERROR, SendResult.FAILED);

        for (Map.Entry<MessagingException.Code, SendResult> entry : expected.entrySet()) {
            CompletableFuture<SendResult> transport = new CompletableFuture<>();
            transport.completeExceptionally(new MessagingException(entry.getKey(), "test transport result"));
            outbound.nextSendStage = transport;
            SendReceipt receipt = scope.channel("islands:send-status").send(Endpoint.proxy(), new byte[0])
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(entry.getValue(), receipt.result(), "mapping for " + entry.getKey());
        }

        CompletableFuture<SendResult> closedTransport = new CompletableFuture<>();
        closedTransport.completeExceptionally(new MessagingException(MessagingException.Code.CLOSED, "closed"));
        outbound.nextSendStage = closedTransport;
        CompletionStage<SendReceipt> closed = scope.channel("islands:send-status").send(Endpoint.proxy(), new byte[0]);
        assertEquals(MessagingException.Code.CLOSED, failure(closed).code());
        scope.close();
    }

    @Test
    void sendOwnDeadlineReturnsTimedOutReceiptAndCancelsTransportStage() throws Exception {
        ManualTimeoutScheduler manualScheduler = new ManualTimeoutScheduler();
        TestOutbound manualOutbound = new TestOutbound();
        LocalMessaging manualHost = new LocalMessaging(self, manualOutbound, DIRECT, manualScheduler);
        try {
            manualOutbound.neverCompleteSends = true;
            Messaging scope = manualHost.openScope("manual-send-timeout");
            CompletableFuture<SendReceipt> pending = scope.channel("islands:send-timeout")
                    .send(Endpoint.proxy(), new byte[0]).toCompletableFuture();
            assertEquals(1, manualOutbound.sendFutures.size());

            manualScheduler.fireTimeout();

            SendReceipt receipt = pending.get(1, TimeUnit.SECONDS);
            assertEquals(SendResult.TIMED_OUT, receipt.result());
            assertTrue(manualOutbound.sendFutures.get(0).isCancelled(),
                    "deadline conversion must still cancel the underlying transport stage");
            scope.close();
        } finally {
            manualHost.close();
            manualScheduler.shutdownNow();
        }
    }

    @Test
    void queuedPublicCompletionsRemainCoveredByOutboundAdmissionLimit() throws Exception {
        QueuedExecutor completions = new QueuedExecutor();
        ScheduledThreadPoolExecutor localScheduler = new ScheduledThreadPoolExecutor(1);
        TestOutbound fastOutbound = new TestOutbound();
        LocalMessaging localHost = new LocalMessaging(self, fastOutbound, DIRECT, localScheduler, completions);
        try {
            Messaging scope = localHost.openScope("queued-completions");
            List<CompletionStage<SendReceipt>> accepted = new ArrayList<CompletionStage<SendReceipt>>();
            for (int index = 0; index < LocalMessaging.MAX_IN_FLIGHT; index++) {
                accepted.add(scope.channel("islands:bounded").send(Endpoint.proxy(), new byte[0]));
            }
            assertEquals(LocalMessaging.MAX_IN_FLIGHT, completions.size());

            CompletableFuture<SendReceipt> rejected = scope.channel("islands:bounded")
                    .send(Endpoint.proxy(), new byte[0]).toCompletableFuture();
            assertTrue(rejected.isDone(), "pre-admission rejection must complete without entering the queue");
            assertEquals(SendResult.BACKPRESSURED, rejected.get(1, TimeUnit.SECONDS).result());
            assertEquals(LocalMessaging.MAX_IN_FLIGHT, completions.size());

            completions.drain();
            for (CompletionStage<SendReceipt> receipt : accepted) {
                assertEquals(SendResult.ACCEPTED, receipt.toCompletableFuture().get(1, TimeUnit.SECONDS).result());
            }

            CompletionStage<SendReceipt> recovered = scope.channel("islands:bounded")
                    .send(Endpoint.proxy(), new byte[0]);
            assertEquals(1, completions.size());
            completions.drain();
            assertEquals(SendResult.ACCEPTED, recovered.toCompletableFuture().get(1, TimeUnit.SECONDS).result());
            scope.close();
        } finally {
            localHost.close();
            localScheduler.shutdownNow();
        }
    }

    @Test
    void closingScopeCancelsUnderlyingOutboundRequest() throws Exception {
        Messaging scope = host.openScope("close-request");
        CompletableFuture<Message> transport = new CompletableFuture<Message>();
        outbound.nextRequestStage = transport;

        CompletionStage<Message> pending = scope.channel("islands:close").request(Endpoint.proxy(), new byte[0],
                Duration.ofSeconds(1));
        assertFalse(pending.toCompletableFuture().isDone());
        scope.close();

        assertTrue(transport.isCancelled(), "closing the owner scope must cancel its outbound request stage");
        assertEquals(MessagingException.Code.CLOSED, failure(pending).code());
    }

    private Message request(String channel) {
        return Message.request(channel, Endpoint.proxy(), self, new byte[0]);
    }

    private static MessagingException failure(CompletionStage<?> stage) throws Exception {
        try {
            stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
            throw new AssertionError("expected stage failure");
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            assertTrue(cause instanceof MessagingException, "unexpected failure: " + cause);
            return (MessagingException) cause;
        }
    }

    private static final class TestOutbound implements LocalMessaging.Outbound {
        private final List<Message> requests = Collections.synchronizedList(new ArrayList<Message>());
        private final Map<UUID, CompletableFuture<Message>> requestFutures =
                Collections.synchronizedMap(new LinkedHashMap<UUID, CompletableFuture<Message>>());
        private final List<CompletableFuture<SendResult>> sendFutures =
                Collections.synchronizedList(new ArrayList<CompletableFuture<SendResult>>());
        private volatile boolean neverCompleteSends;
        private volatile CompletableFuture<Message> nextRequestStage;
        private volatile CompletableFuture<SendResult> nextSendStage;

        @Override public CompletionStage<SendResult> send(Message message) {
            CompletableFuture<SendResult> future = nextSendStage;
            nextSendStage = null;
            if (future == null) {
                future = neverCompleteSends ? new CompletableFuture<SendResult>()
                        : CompletableFuture.completedFuture(SendResult.ACCEPTED);
            }
            sendFutures.add(future);
            return future;
        }

        @Override public CompletionStage<Message> request(Message request, Duration timeout) {
            requests.add(request);
            CompletableFuture<Message> future = nextRequestStage;
            nextRequestStage = null;
            if (future == null) future = new CompletableFuture<Message>();
            requestFutures.put(request.id(), future);
            return future;
        }

        @Override public CompletionStage<PublishResult> publish(Message event) {
            return CompletableFuture.completedFuture(new PublishResult(event.id(), Collections.<Endpoint, SendResult>emptyMap()));
        }

    }

    private static final class QueuedExecutor implements Executor {
        private final List<Runnable> queued = Collections.synchronizedList(new ArrayList<Runnable>());

        @Override public void execute(Runnable command) { queued.add(command); }
        private void clear() { queued.clear(); }
        private int size() { return queued.size(); }
        private void drain() {
            while (true) {
                Runnable task;
                synchronized (queued) {
                    if (queued.isEmpty()) return;
                    task = queued.remove(0);
                }
                task.run();
            }
        }
    }

    private static final class ManualTimeoutScheduler extends ScheduledThreadPoolExecutor {
        private volatile Runnable timeout;

        private ManualTimeoutScheduler() { super(1); }

        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            timeout = command;
            return super.schedule(new Runnable() { @Override public void run() { } }, 1, TimeUnit.DAYS);
        }

        private void fireTimeout() {
            Runnable scheduled = timeout;
            if (scheduled == null) throw new AssertionError("no operation timeout was armed");
            scheduled.run();
        }
    }
}

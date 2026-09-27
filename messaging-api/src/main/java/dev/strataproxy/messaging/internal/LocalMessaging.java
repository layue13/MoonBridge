package dev.strataproxy.messaging.internal;

import dev.strataproxy.messaging.Endpoint;
import dev.strataproxy.messaging.Message;
import dev.strataproxy.messaging.MessageChannel;
import dev.strataproxy.messaging.MessageHandler;
import dev.strataproxy.messaging.MessageKind;
import dev.strataproxy.messaging.Messaging;
import dev.strataproxy.messaging.MessagingException;
import dev.strataproxy.messaging.PublishResult;
import dev.strataproxy.messaging.SendReceipt;
import dev.strataproxy.messaging.SendResult;
import dev.strataproxy.messaging.Subscription;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Host-side scope registry and bounded local message dispatcher. The host owns the supplied scheduler and
 * executors and must keep the completion executor-independent messaging service alive until it is closed.
 * Plugin handlers/listeners run on their scope executor; public operation futures complete on a separate
 * completion executor so closing a plugin executor cannot strand them or run continuations on a transport reader.
 */
public final class LocalMessaging implements AutoCloseable {
    public static final int MAX_IN_FLIGHT = 128;
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration MAX_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Executor COMPLETION_EXECUTOR = ForkJoinPool.commonPool();

    private final Endpoint self;
    private final Outbound outbound;
    private final Executor defaultExecutor;
    private final Executor completionExecutor;
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();
    private final Map<String, Scope> scopes = new HashMap<String, Scope>();
    private final Map<String, ChannelEntry> channels = new HashMap<String, ChannelEntry>();
    private final Set<Operation<?>> operations = new HashSet<Operation<?>>();
    private boolean closed;
    private int outboundInFlight;
    private int inboundInFlight;

    public LocalMessaging(Endpoint self, Outbound outbound, Executor defaultExecutor,
                          ScheduledExecutorService scheduler) {
        this(self, outbound, defaultExecutor, scheduler, COMPLETION_EXECUTOR);
    }

    /** Package-private constructor for deterministic completion-dispatch tests. */
    LocalMessaging(Endpoint self, Outbound outbound, Executor defaultExecutor,
                   ScheduledExecutorService scheduler, Executor completionExecutor) {
        this.self = Objects.requireNonNull(self, "self");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.defaultExecutor = Objects.requireNonNull(defaultExecutor, "defaultExecutor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
    }

    /** Opens an exclusively named plugin scope using the host's default callback executor. */
    public Messaging openScope(String owner) {
        return openScope(owner, defaultExecutor);
    }

    /** Opens an exclusively named plugin scope using the supplied callback executor. */
    public Messaging openScope(String owner, Executor executor) {
        Objects.requireNonNull(owner, "owner");
        if (owner.trim().isEmpty()) throw new IllegalArgumentException("owner must not be blank");
        Objects.requireNonNull(executor, "executor");
        synchronized (lock) {
            ensureHostOpen();
            if (scopes.containsKey(owner)) {
                throw new MessagingException(MessagingException.Code.REJECTED,
                        "messaging scope already exists: " + owner);
            }
            Scope scope = new Scope(owner, executor);
            scopes.put(owner, scope);
            return scope;
        }
    }

    /**
     * Enqueues an inbound event for each active local listener. The result describes only local queue
     * acceptance, not callback success. Queue slots are released if an owner closes while callbacks remain
     * queued, even when its executor discards those callbacks.
     */
    public SendResult receiveEvent(Message message) {
        Objects.requireNonNull(message, "message");
        if (message.kind() != MessageKind.EVENT) {
            throw new IllegalArgumentException("message must have EVENT kind");
        }
        if (message.target() != null && !self.equals(message.target())) {
            throw new IllegalArgumentException("event targets a different endpoint");
        }
        final List<Registration> listeners;
        final List<EventDelivery> deliveries;
        synchronized (lock) {
            if (closed) return SendResult.REJECTED;
            ChannelEntry entry = channels.get(message.channel());
            listeners = entry == null ? Collections.<Registration>emptyList()
                    : activeListeners(entry);
            if (listeners.isEmpty()) return SendResult.NO_SUBSCRIBER;
            if (listeners.size() > MAX_IN_FLIGHT - inboundInFlight) return SendResult.BACKPRESSURED;
            inboundInFlight += listeners.size();
            deliveries = new ArrayList<EventDelivery>(listeners.size());
            for (Registration listener : listeners) {
                EventDelivery delivery = new EventDelivery(listener.scope);
                listener.scope.deliveries.add(delivery);
                deliveries.add(delivery);
            }
        }

        boolean allEnqueued = true;
        for (int index = 0; index < listeners.size(); index++) {
            Registration listener = listeners.get(index);
            EventDelivery delivery = deliveries.get(index);
            try {
                listener.scope.executor.execute(new Runnable() {
                    @Override public void run() {
                        if (delivery.isDone()) return;
                        try {
                            if (isActive(listener)) {
                                @SuppressWarnings("unchecked") Consumer<Message> consumer =
                                        (Consumer<Message>) listener.callback;
                                consumer.accept(message);
                            }
                        } catch (Throwable ignored) {
                            // One listener failing must not prevent other listeners from seeing the event.
                        } finally {
                            delivery.finish();
                        }
                    }
                });
            } catch (RejectedExecutionException rejected) {
                delivery.finish();
                allEnqueued = false;
            }
        }
        return allEnqueued ? SendResult.ACCEPTED : SendResult.BACKPRESSURED;
    }

    /**
     * Dispatches an inbound request and completes with its correlated reply. The deadline includes waiting
     * for the owning callback executor and asynchronous handler completion.
     */
    public CompletionStage<Message> receiveRequest(Message request, Duration timeout) {
        Objects.requireNonNull(request, "request");
        if (request.kind() != MessageKind.REQUEST) {
            throw new IllegalArgumentException("message must have REQUEST kind");
        }
        if (!self.equals(request.target())) {
            throw new IllegalArgumentException("request targets a different endpoint");
        }
        final long timeoutMillis = timeoutMillis(timeout);
        final Registration handler;
        final Operation<Message> operation;
        synchronized (lock) {
            if (closed) return failed(new MessagingException(MessagingException.Code.CLOSED,
                    "messaging host is closed"));
            ChannelEntry entry = channels.get(request.channel());
            handler = entry == null ? null : activeHandler(entry);
            if (handler == null) return failed(new MessagingException(MessagingException.Code.NO_HANDLER,
                    "no request handler for channel " + request.channel()));
            if (inboundInFlight >= MAX_IN_FLIGHT) return failed(new MessagingException(
                    MessagingException.Code.BACKPRESSURED, "inbound request limit reached"));
            inboundInFlight++;
            operation = new Operation<Message>(handler.scope, new Runnable() {
                @Override public void run() { inboundInFlight--; }
            });
            operations.add(operation);
            handler.scope.operations.add(operation);
        }
        if (!operation.armTimeout(timeoutMillis)) return operation.future;
        try {
            handler.scope.executor.execute(new Runnable() {
                @Override public void run() {
                    if (operation.isDone() || !isActive(handler)) {
                        operation.fail(new MessagingException(MessagingException.Code.CLOSED,
                                "request handler scope is closed"));
                        return;
                    }
                    CompletionStage<byte[]> response;
                    try {
                        response = ((MessageHandler) handler.callback).handle(request);
                        if (response == null) throw new NullPointerException("handler returned null stage");
                    } catch (Throwable failure) {
                        operation.fail(handlerFailure(failure));
                        return;
                    }
                    operation.setCancellation(response);
                    response.whenComplete((payload, failure) -> {
                        if (failure != null) {
                            operation.fail(handlerFailure(unwrap(failure)));
                        } else if (payload == null) {
                            operation.fail(new MessagingException(MessagingException.Code.HANDLER_FAILED,
                                    "request handler returned null payload"));
                        } else {
                            try {
                                operation.succeed(Message.reply(request, self, payload));
                            } catch (Throwable invalid) {
                                operation.fail(handlerFailure(invalid));
                            }
                        }
                    });
                }
            });
        } catch (RejectedExecutionException rejected) {
            operation.fail(new MessagingException(MessagingException.Code.BACKPRESSURED,
                    "request handler executor rejected work", rejected));
        }
        return operation.future;
    }

    /** Returns a snapshot of channels and their current local subscription flags. */
    public Map<String, Integer> subscriptions() {
        synchronized (lock) {
            return subscriptionSnapshotLocked();
        }
    }

    @Override
    public void close() {
        List<Scope> toClose;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            toClose = new ArrayList<Scope>(scopes.values());
        }
        for (Scope scope : toClose) scope.close();
    }

    private void closeScope(Scope scope) {
        List<Registration> registrations;
        List<Operation<?>> pending;
        List<EventDelivery> deliveries;
        synchronized (lock) {
            if (!scope.active) return;
            scope.active = false;
            scopes.remove(scope.owner);
            registrations = new ArrayList<Registration>(scope.registrations);
            pending = new ArrayList<Operation<?>>(scope.operations);
            deliveries = new ArrayList<EventDelivery>(scope.deliveries);
            scope.deliveries.clear();
            scope.registrations.clear();
            for (Registration registration : registrations) {
                registration.active = false;
                removeRegistrationLocked(registration);
            }
        }
        for (Operation<?> operation : pending) {
            operation.fail(new MessagingException(MessagingException.Code.CLOSED,
                    "messaging scope is closed: " + scope.owner));
        }
        for (EventDelivery delivery : deliveries) delivery.finish();
    }

    private Subscription register(Scope scope, String channel, Object callback, boolean request) {
        Message.validateChannel(channel);
        Objects.requireNonNull(callback, "callback");
        Registration registration = new Registration(scope, channel, callback, request);
        synchronized (lock) {
            ensureScopeOpen(scope);
            ChannelEntry entry = channels.get(channel);
            if (entry == null) {
                entry = new ChannelEntry();
                channels.put(channel, entry);
            }
            if (request && entry.requestHandler != null && entry.requestHandler.active) {
                throw new MessagingException(MessagingException.Code.REJECTED,
                        "request handler already registered for channel " + channel);
            }
            if (request) entry.requestHandler = registration;
            else entry.listeners.add(registration);
            scope.registrations.add(registration);
        }
        return registration;
    }

    private void unregister(Registration registration) {
        synchronized (lock) {
            if (!registration.active) return;
            registration.active = false;
            registration.scope.registrations.remove(registration);
            removeRegistrationLocked(registration);
        }
    }

    private void removeRegistrationLocked(Registration registration) {
        ChannelEntry entry = channels.get(registration.channel);
        if (entry == null) return;
        if (entry.requestHandler == registration) entry.requestHandler = null;
        entry.listeners.remove(registration);
        if (entry.requestHandler == null && entry.listeners.isEmpty()) channels.remove(registration.channel);
    }

    private Map<String, Integer> subscriptionSnapshotLocked() {
        Map<String, Integer> snapshot = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, ChannelEntry> entry : channels.entrySet()) {
            int flags = 0;
            for (Registration listener : entry.getValue().listeners) {
                if (listener.active && listener.scope.active) { flags |= 1; break; }
            }
            Registration handler = entry.getValue().requestHandler;
            if (handler != null && handler.active && handler.scope.active) flags |= 2;
            if (flags != 0) snapshot.put(entry.getKey(), flags);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private List<Registration> activeListeners(ChannelEntry entry) {
        List<Registration> result = new ArrayList<Registration>();
        for (Registration listener : entry.listeners) {
            if (listener.active && listener.scope.active) result.add(listener);
        }
        return result;
    }

    private Registration activeHandler(ChannelEntry entry) {
        Registration registration = entry.requestHandler;
        return registration != null && registration.active && registration.scope.active ? registration : null;
    }

    private boolean isActive(Registration registration) {
        synchronized (lock) {
            return !closed && registration.active && registration.scope.active;
        }
    }

    private boolean reserveOutbound(Scope scope, Operation<?> operation) {
        synchronized (lock) {
            if (closed || !scope.active) {
                operation.failLocked(new MessagingException(MessagingException.Code.CLOSED,
                        "messaging scope is closed: " + scope.owner));
                return false;
            }
            if (outboundInFlight >= MAX_IN_FLIGHT) {
                operation.failLocked(new MessagingException(MessagingException.Code.BACKPRESSURED,
                        "outbound operation limit reached"));
                return false;
            }
            outboundInFlight++;
            operations.add(operation);
            scope.operations.add(operation);
            return true;
        }
    }

    private void operationFinished(Operation<?> operation) {
        synchronized (lock) {
            operations.remove(operation);
            operation.scope.operations.remove(operation);
            operation.release.run();
        }
    }

    private void completePublic(Runnable task) {
        try {
            completionExecutor.execute(task);
        } catch (RejectedExecutionException rejected) {
            Thread fallback = new Thread(task, "strataproxy-message-completion");
            fallback.setDaemon(true);
            fallback.start();
        }
    }

    private void completeOperation(Operation<?> operation, Runnable completion) {
        completePublic(new Runnable() {
            @Override public void run() {
                // Keep the admission slot while this public completion is queued. Release before invoking
                // CompletableFuture continuations, which may run arbitrary user code inline.
                operationFinished(operation);
                completion.run();
            }
        });
    }

    private static long timeoutMillis(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        final long millis;
        try {
            millis = timeout.toMillis();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timeout is too large", overflow);
        }
        if (millis <= 0 || millis > MAX_REQUEST_TIMEOUT.toMillis()) {
            throw new IllegalArgumentException("timeout must be positive and at most 60 seconds");
        }
        return millis;
    }

    private static MessagingException handlerFailure(Throwable failure) {
        return new MessagingException(MessagingException.Code.HANDLER_FAILED,
                "message handler failed", failure);
    }

    private static SendReceipt sendReceiptForFailure(UUID messageId, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof MessagingException) {
            MessagingException messagingFailure = (MessagingException) cause;
            switch (messagingFailure.code()) {
                case TIMED_OUT: return new SendReceipt(messageId, SendResult.TIMED_OUT);
                case BACKPRESSURED: return new SendReceipt(messageId, SendResult.BACKPRESSURED);
                case NOT_CONNECTED: return new SendReceipt(messageId, SendResult.NOT_CONNECTED);
                case NO_HANDLER: return new SendReceipt(messageId, SendResult.NO_SUBSCRIBER);
                case REJECTED: return new SendReceipt(messageId, SendResult.REJECTED);
                case HANDLER_FAILED:
                case PROTOCOL_ERROR: return new SendReceipt(messageId, SendResult.FAILED);
                case CLOSED: throw messagingFailure;
                default: return new SendReceipt(messageId, SendResult.FAILED);
            }
        }
        return new SendReceipt(messageId, SendResult.FAILED);
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(failure);
        return future;
    }

    private void ensureHostOpen() {
        if (closed) throw new MessagingException(MessagingException.Code.CLOSED, "messaging host is closed");
    }

    private void ensureScopeOpen(Scope scope) {
        ensureHostOpen();
        if (!scope.active) throw new MessagingException(MessagingException.Code.CLOSED,
                "messaging scope is closed: " + scope.owner);
    }

    /** Host transport boundary. The source identity is already fixed in each message. */
    public interface Outbound {
        CompletionStage<SendResult> send(Message message);
        CompletionStage<Message> request(Message request, Duration timeout);
        CompletionStage<PublishResult> publish(Message event);
    }

    private final class Scope implements Messaging {
        private final String owner;
        private final Executor executor;
        private final Set<Registration> registrations = new HashSet<Registration>();
        private final Set<Operation<?>> operations = new HashSet<Operation<?>>();
        private final Set<EventDelivery> deliveries = new HashSet<EventDelivery>();
        private volatile boolean active = true;

        private Scope(String owner, Executor executor) {
            this.owner = owner;
            this.executor = executor;
        }

        @Override public MessageChannel channel(String name) {
            Message.validateChannel(name);
            synchronized (lock) { ensureScopeOpen(this); }
            return new Channel(this, name);
        }

        @Override public void close() { closeScope(this); }
    }

    private final class Channel implements MessageChannel {
        private final Scope scope;
        private final String name;

        private Channel(Scope scope, String name) {
            this.scope = scope;
            this.name = name;
        }

        @Override public String name() { return name; }

        @Override public Subscription subscribe(Consumer<Message> listener) {
            return register(scope, name, Objects.requireNonNull(listener, "listener"), false);
        }

        @Override public Subscription onRequest(MessageHandler handler) {
            return register(scope, name, Objects.requireNonNull(handler, "handler"), true);
        }

        @Override public CompletionStage<SendReceipt> send(Endpoint target, byte[] payload) {
            Objects.requireNonNull(target, "target");
            final Message message = Message.event(name, self, target, payload);
            final Operation<SendReceipt> operation = new Operation<SendReceipt>(scope,
                    new Runnable() { @Override public void run() { outboundInFlight--; } },
                    failure -> sendReceiptForFailure(message.id(), failure));
            if (!reserveOutbound(scope, operation)) return operation.future;
            if (!operation.armTimeout(DEFAULT_REQUEST_TIMEOUT.toMillis())) return operation.future;
            if (operation.isDone()) return operation.future;
            CompletionStage<SendResult> stage;
            try {
                stage = Objects.requireNonNull(outbound.send(message), "outbound send stage");
            } catch (Throwable failure) {
                operation.fail(failure);
                return operation.future;
            }
            operation.setCancellation(stage);
            stage.whenComplete((result, failure) -> {
                if (failure != null) operation.fail(unwrap(failure));
                else if (result == null) operation.fail(new MessagingException(
                        MessagingException.Code.PROTOCOL_ERROR, "outbound returned null send result"));
                else operation.succeed(new SendReceipt(message.id(), result));
            });
            return operation.future;
        }

        @Override public CompletionStage<PublishResult> publish(byte[] payload) {
            final Message event = Message.event(name, self, null, payload);
            final Operation<PublishResult> operation = new Operation<PublishResult>(scope,
                    new Runnable() { @Override public void run() { outboundInFlight--; } });
            if (!reserveOutbound(scope, operation)) return operation.future;
            if (!operation.armTimeout(DEFAULT_REQUEST_TIMEOUT.toMillis())) return operation.future;
            if (operation.isDone()) return operation.future;
            CompletionStage<PublishResult> stage;
            try {
                stage = Objects.requireNonNull(outbound.publish(event), "outbound publish stage");
            } catch (Throwable failure) {
                operation.fail(failure);
                return operation.future;
            }
            operation.setCancellation(stage);
            stage.whenComplete((result, failure) -> {
                if (failure != null) operation.fail(unwrap(failure));
                else if (result == null || !event.id().equals(result.messageId())) operation.fail(
                        new MessagingException(MessagingException.Code.PROTOCOL_ERROR,
                                "outbound returned invalid publish result"));
                else operation.succeed(result);
            });
            return operation.future;
        }

        @Override public CompletionStage<Message> request(Endpoint target, byte[] payload) {
            return request(target, payload, DEFAULT_REQUEST_TIMEOUT);
        }

        @Override public CompletionStage<Message> request(Endpoint target, byte[] payload, Duration timeout) {
            Objects.requireNonNull(target, "target");
            final long timeoutMillis = timeoutMillis(timeout);
            final Message request = Message.request(name, self, target, payload);
            final Operation<Message> operation = new Operation<Message>(scope,
                    new Runnable() { @Override public void run() { outboundInFlight--; } });
            if (!reserveOutbound(scope, operation)) return operation.future;
            if (!operation.armTimeout(timeoutMillis)) return operation.future;
            if (operation.isDone()) return operation.future;
            CompletionStage<Message> stage;
            try {
                stage = Objects.requireNonNull(outbound.request(request, Duration.ofMillis(timeoutMillis)),
                        "outbound request stage");
            } catch (Throwable failure) {
                operation.fail(failure);
                return operation.future;
            }
            operation.setCancellation(stage);
            stage.whenComplete((reply, failure) -> {
                if (failure != null) {
                    operation.fail(unwrap(failure));
                } else if (!isValidReply(request, reply)) {
                    operation.fail(new MessagingException(MessagingException.Code.PROTOCOL_ERROR,
                            "outbound returned an uncorrelated reply"));
                } else {
                    operation.succeed(reply);
                }
            });
            return operation.future;
        }
    }

    private boolean isValidReply(Message request, Message reply) {
        return reply != null && reply.kind() == MessageKind.REPLY
                && request.id().equals(reply.replyTo())
                && self.equals(reply.target())
                && request.target().equals(reply.source())
                && request.channel().equals(reply.channel());
    }

    private final class Registration implements Subscription {
        private final Scope scope;
        private final String channel;
        private final Object callback;
        private final boolean request;
        private volatile boolean active = true;

        private Registration(Scope scope, String channel, Object callback, boolean request) {
            this.scope = scope;
            this.channel = channel;
            this.callback = callback;
            this.request = request;
        }

        @Override public void close() { unregister(this); }
    }

    private final class ChannelEntry {
        private final List<Registration> listeners = new ArrayList<Registration>();
        private Registration requestHandler;
    }

    private final class Operation<T> {
        private final Scope scope;
        private final Runnable release;
        private final Function<Throwable, T> failureMapper;
        private final OperationFuture<T> future = new OperationFuture<T>(this);
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile ScheduledFuture<?> timer;
        private volatile CompletionStage<?> underlying;

        private Operation(Scope scope, Runnable release) {
            this(scope, release, null);
        }

        private Operation(Scope scope, Runnable release, Function<Throwable, T> failureMapper) {
            this.scope = scope;
            this.release = release;
            this.failureMapper = failureMapper;
        }

        private boolean isDone() { return done.get(); }

        private boolean armTimeout(long millis) {
            if (done.get()) return false;
            try {
                ScheduledFuture<?> scheduled = scheduler.schedule(new Runnable() {
                    @Override public void run() {
                        fail(new MessagingException(MessagingException.Code.TIMED_OUT,
                                "message request timed out"));
                    }
                }, millis, TimeUnit.MILLISECONDS);
                timer = scheduled;
                if (done.get()) {
                    scheduled.cancel(false);
                    return false;
                }
                return true;
            } catch (RejectedExecutionException rejected) {
                fail(new MessagingException(MessagingException.Code.CLOSED,
                        "request timer is unavailable", rejected));
                return false;
            }
        }

        private void succeed(T value) { finish(value, null); }

        private void fail(Throwable failure) { finish(null, failure); }

        private void failLocked(Throwable failure) {
            if (!done.compareAndSet(false, true)) return;
            // This operation was rejected before admission and its future has not escaped the API call.
            completeFailureNow(failure);
        }

        private void setCancellation(CompletionStage<?> stage) {
            underlying = stage;
            if (done.get()) cancelUnderlying();
        }

        private void cancel() {
            if (!done.compareAndSet(false, true)) return;
            ScheduledFuture<?> scheduled = timer;
            if (scheduled != null) scheduled.cancel(false);
            cancelUnderlying();
            operationFinished(this);
        }

        private void finish(T value, Throwable failure) {
            if (!done.compareAndSet(false, true)) return;
            ScheduledFuture<?> scheduled = timer;
            if (scheduled != null) scheduled.cancel(false);
            if (failure instanceof MessagingException) {
                MessagingException.Code code = ((MessagingException) failure).code();
                if (code == MessagingException.Code.TIMED_OUT || code == MessagingException.Code.CLOSED) {
                    cancelUnderlying();
                }
            }
            if (failure == null) {
                completeOperation(this, new Runnable() { @Override public void run() { future.complete(value); } });
            } else {
                completeOperation(this, new Runnable() {
                    @Override public void run() { completeFailureNow(failure); }
                });
            }
        }

        private void completeFailureNow(Throwable failure) {
            if (failureMapper == null) {
                future.completeExceptionally(failure);
                return;
            }
            try { future.complete(failureMapper.apply(failure)); }
            catch (Throwable mappingFailure) { future.completeExceptionally(mappingFailure); }
        }

        private void cancelUnderlying() {
            CompletionStage<?> stage = underlying;
            if (stage != null) {
                try { stage.toCompletableFuture().cancel(true); } catch (Throwable ignored) { }
            }
        }
    }

    private final class EventDelivery {
        private final Scope scope;
        private final AtomicBoolean done = new AtomicBoolean();

        private EventDelivery(Scope scope) { this.scope = scope; }
        private boolean isDone() { return done.get(); }

        private void finish() {
            if (!done.compareAndSet(false, true)) return;
            synchronized (lock) {
                scope.deliveries.remove(this);
                inboundInFlight--;
            }
        }
    }

    private final class OperationFuture<T> extends CompletableFuture<T> {
        private final Operation<T> operation;

        private OperationFuture(Operation<T> operation) { this.operation = operation; }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) operation.cancel();
            return cancelled;
        }
    }
}

package dev.strataproxy.backendchannel;

import dev.strataproxy.messaging.Endpoint;
import dev.strataproxy.messaging.Message;
import dev.strataproxy.messaging.MessageKind;
import dev.strataproxy.messaging.Messaging;
import dev.strataproxy.messaging.MessagingException;
import dev.strataproxy.messaging.PublishResult;
import dev.strataproxy.messaging.SendResult;
import dev.strataproxy.messaging.internal.LocalMessaging;
import dev.strataproxy.messaging.protocol.MessageCodec;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Java 8-compatible reconnecting transport with process-local messaging scopes. */
public final class BackendChannelClient implements AutoCloseable, LocalMessaging.Outbound {
    public static final int MAX_PENDING_REQUESTS = LocalMessaging.MAX_IN_FLIGHT;
    public static final int MAX_HANDLER_QUEUE = 128;
    public static final int MAX_WRITER_QUEUE = 128;
    public static final long MAX_WRITER_QUEUE_BYTES = 1024L * 1024L;

    private static final long DEFAULT_MESSAGE_TIMEOUT_MILLIS = 5000L;
    private static final long CLOSE_FLUSH_MILLIS = 100L;
    private static final long WRITE_COMPLETION_TIMEOUT_MILLIS = 5000L;

    private final String host, instanceId, backendName, gameAddress, generation, keyId;
    private final int port;
    private final byte[] secret;
    private final long heartbeatMillis, reconnectMinMillis, reconnectMaxMillis;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Session> session = new AtomicReference<Session>();
    private volatile Session connecting;
    private final ThreadPoolExecutor handlerExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(MAX_HANDLER_QUEUE), namedFactory("backend-channel-handler"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(2, namedFactory("backend-channel-timer"));
    private final LocalMessaging localMessaging;
    private final Object stateMonitor = new Object();
    private volatile boolean registered;
    private volatile long epoch;
    private final Thread connectionThread;

    public BackendChannelClient(String host, int port, String instanceId, String backendName, String gameAddress,
                                String generation, String keyId, byte[] secret) {
        this(host, port, instanceId, backendName, gameAddress, generation, keyId, secret,
                10_000L, 500L, 10_000L);
    }

    public BackendChannelClient(String host, int port, String instanceId, String backendName, String gameAddress,
                                String generation, String keyId, byte[] secret, long heartbeatMillis,
                                long reconnectMinMillis, long reconnectMaxMillis) {
        if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("host is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range");
        if (secret == null || secret.length < 32) throw new IllegalArgumentException("secret must contain at least 32 bytes");
        if (heartbeatMillis < 1000L || reconnectMinMillis < 1L || reconnectMaxMillis < reconnectMinMillis) {
            throw new IllegalArgumentException("invalid timing configuration");
        }
        validateIdentity(instanceId, "instanceId");
        validateIdentity(backendName, "backendName");
        validateIdentity(gameAddress, "gameAddress");
        validateIdentity(generation, "generation");
        validateIdentity(keyId, "keyId");
        this.host = host;
        this.port = port;
        this.instanceId = instanceId;
        this.backendName = backendName;
        this.gameAddress = gameAddress;
        this.generation = generation;
        this.keyId = keyId;
        this.secret = Arrays.copyOf(secret, secret.length);
        this.heartbeatMillis = heartbeatMillis;
        this.reconnectMinMillis = reconnectMinMillis;
        this.reconnectMaxMillis = reconnectMaxMillis;
        this.timer.setRemoveOnCancelPolicy(true);
        this.localMessaging = new LocalMessaging(Endpoint.backend(backendName), this, handlerExecutor, timer);
        this.connectionThread = new Thread(new Runnable() {
            @Override public void run() { reconnectLoop(); }
        }, "backend-channel-connection");
        this.connectionThread.setDaemon(true);
        this.connectionThread.start();
    }

    /** Opens a plugin-owned scope on this client's shared control connection. */
    public Messaging messaging(String owner) {
        return localMessaging.openScope(owner);
    }

    /** Opens a plugin-owned scope using the supplied callback executor. */
    public Messaging messaging(String owner, java.util.concurrent.Executor executor) {
        return localMessaging.openScope(owner, executor);
    }

    /** Returns true after REGISTERED and until the active connection is lost. */
    public boolean isRegistered() { return registered; }

    /** Waits for initial registration or a later reconnect. */
    public boolean awaitRegistered(long timeout, TimeUnit unit) throws InterruptedException {
        if (timeout < 0) throw new IllegalArgumentException("timeout must not be negative");
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (stateMonitor) {
            while (!registered && !closed.get()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(stateMonitor, left);
            }
            return registered;
        }
    }

    public long getEpoch() { return epoch; }

    @Override public void close() {
        final Session active;
        final Session inProgress;
        synchronized (stateMonitor) {
            if (!closed.compareAndSet(false, true)) return;
            active = session.getAndSet(null);
            inProgress = connecting;
            connecting = null;
            registered = false;
            stateMonitor.notifyAll();
        }
        localMessaging.close();
        if (active != null) {
            try {
                CompletableFuture<Void> goodbye = new CompletableFuture<Void>();
                if (active.enqueue(Wire.empty(Wire.GOODBYE),
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_FLUSH_MILLIS), null, goodbye)) {
                    active.awaitWriter(goodbye, CLOSE_FLUSH_MILLIS);
                }
            } catch (IOException ignored) { }
            active.close(new MessagingException(MessagingException.Code.CLOSED, "backend channel client closed"));
        }
        if (inProgress != null && inProgress != active) inProgress.close(new IOException("backend channel client closed"));
        connectionThread.interrupt();
        handlerExecutor.shutdownNow();
        timer.shutdownNow();
        Arrays.fill(secret, (byte) 0);
    }

    @Override public CompletionStage<SendResult> send(Message message) {
        if (message == null || message.kind() != MessageKind.EVENT || message.target() == null) {
            return failed(new MessagingException(MessagingException.Code.REJECTED, "send requires a targeted event"));
        }
        Message authenticated = withLocalSource(message);
        Session active = activeSession();
        if (active == null) return CompletableFuture.completedFuture(SendResult.NOT_CONNECTED);
        CompletableFuture<MessageCodec.Response> operation = sendOperation(active, authenticated,
                DEFAULT_MESSAGE_TIMEOUT_MILLIS, MessageCodec.Response.Type.SEND);
        return mapOperation(operation, response -> response.sendResult, failure -> {
                    Throwable cause = unwrap(failure);
                    if (cause instanceof MessagingException) {
                        MessagingException.Code code = ((MessagingException) cause).code();
                        if (code == MessagingException.Code.NOT_CONNECTED) return SendResult.NOT_CONNECTED;
                        if (code == MessagingException.Code.BACKPRESSURED) return SendResult.BACKPRESSURED;
                        if (code == MessagingException.Code.TIMED_OUT) return SendResult.TIMED_OUT;
                        if (code == MessagingException.Code.REJECTED) return SendResult.REJECTED;
                    }
                    return SendResult.FAILED;
                });
    }

    @Override public CompletionStage<Message> request(Message request, Duration timeout) {
        if (request == null || request.kind() != MessageKind.REQUEST || request.target() == null) {
            return failed(new MessagingException(MessagingException.Code.REJECTED, "request requires a targeted request message"));
        }
        final long timeoutMillis = durationMillis(timeout);
        Message authenticated = withLocalSource(request);
        Session active = activeSession();
        if (active == null) return failed(notConnected());
        return mapOperation(sendOperation(active, authenticated, timeoutMillis, MessageCodec.Response.Type.REPLY),
                response -> response.message, failure -> { throw new CompletionException(failure); });
    }

    @Override public CompletionStage<PublishResult> publish(Message event) {
        if (event == null || event.kind() != MessageKind.EVENT || event.target() != null) {
            return failed(new MessagingException(MessagingException.Code.REJECTED, "publish requires an untargeted event"));
        }
        Message authenticated = withLocalSource(event);
        Session active = activeSession();
        if (active == null) return failed(notConnected());
        return mapOperation(sendOperation(active, authenticated, DEFAULT_MESSAGE_TIMEOUT_MILLIS,
                MessageCodec.Response.Type.PUBLISH), response -> response.publishResult,
                failure -> { throw new CompletionException(failure); });
    }

    private static <T> CompletionStage<T> mapOperation(CompletableFuture<MessageCodec.Response> source,
                                                         Function<MessageCodec.Response, T> success,
                                                         Function<Throwable, T> failureMapper) {
        CompletableFuture<T> mapped = new CompletableFuture<T>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) source.cancel(mayInterruptIfRunning);
                return cancelled;
            }
        };
        source.whenComplete((response, failure) -> {
            if (mapped.isCancelled()) return;
            try {
                if (failure == null) mapped.complete(success.apply(response));
                else mapped.complete(failureMapper.apply(unwrap(failure)));
            } catch (Throwable mappedFailure) {
                mapped.completeExceptionally(mappedFailure);
            }
        });
        return mapped;
    }

    private Message withLocalSource(Message message) {
        return new Message(message.id(), message.kind(), message.channel(), Endpoint.backend(backendName),
                message.target(), message.replyTo(), message.payload());
    }

    private CompletableFuture<MessageCodec.Response> sendOperation(Session active, Message message,
                                                                      long timeoutMillis,
                                                                      MessageCodec.Response.Type expected) {
        if (active != session.get() || !registered) return failed(notConnected());
        if (!active.requestSlots.tryAcquire()) return failed(new MessagingException(
                MessagingException.Code.BACKPRESSURED, "too many pending messaging operations"));
        long operationId = active.nextOperationId.getAndIncrement();
        if (operationId <= 0) {
            active.requestSlots.release();
            return failed(new MessagingException(MessagingException.Code.PROTOCOL_ERROR, "operation ID space exhausted"));
        }
        long now = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        final long deadlineNanos = Long.MAX_VALUE - now < timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
        final byte[] frame;
        try { frame = MessageCodec.message(operationId, timeoutMillis, message); }
        catch (IOException | RuntimeException invalid) {
            active.requestSlots.release();
            return failed(new MessagingException(MessagingException.Code.REJECTED, "message could not be encoded", invalid));
        }
        final PendingOperation pending = new PendingOperation(active, operationId, expected, message, deadlineNanos);
        if (active.pending.putIfAbsent(operationId, pending) != null) {
            active.requestSlots.release();
            return failed(new MessagingException(MessagingException.Code.PROTOCOL_ERROR, "operation ID collision"));
        }
        pending.startTimeout();
        if (pending.isDone()) return pending.future;
        if (!active.enqueue(frame, pending.deadlineNanos, pending, null, message, operationId, timeoutMillis)) {
            pending.fail(active.isClosed() ? notConnected() : new MessagingException(
                    MessagingException.Code.BACKPRESSURED, "backend message writer is full"));
        }
        return pending.future;
    }

    private Session activeSession() {
        Session active = session.get();
        return registered && active != null && !active.isClosed() ? active : null;
    }

    private void reconnectLoop() {
        long pause = reconnectMinMillis;
        while (!closed.get()) {
            Session active = null;
            try {
                Socket socket = new Socket();
                active = new Session(socket);
                synchronized (stateMonitor) {
                    if (closed.get()) { active.close(new IOException("client closed")); break; }
                    connecting = active;
                }
                if (closed.get()) { active.close(new IOException("client closed")); break; }
                socket.connect(new InetSocketAddress(host, port), 5000);
                active.initialize();
                runSession(active);
                pause = reconnectMinMillis;
            } catch (InterruptedException interrupted) {
                if (closed.get()) break;
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Avoid logging peer-controlled protocol details from this transport layer.
            } finally {
                if (active != null) {
                    session.compareAndSet(active, null);
                    active.close(new MessagingException(MessagingException.Code.NOT_CONNECTED, "backend channel disconnected"));
                }
                synchronized (stateMonitor) {
                    if (connecting == active) connecting = null;
                }
                setRegistered(false);
            }
            if (!closed.get()) {
                try { Thread.sleep(pause); } catch (InterruptedException e) { if (closed.get()) break; }
                pause = Math.min(reconnectMaxMillis, pause > reconnectMaxMillis / 2 ? reconnectMaxMillis : pause * 2);
            }
        }
    }

    private void runSession(final Session active) throws Exception {
        active.socket.setSoTimeout(5000);
        Wire.Hello hello = Wire.decodeHello(FrameCodec.read(active.in));
        if (hello.version != Wire.VERSION) throw new IOException("unsupported proxy protocol version");
        byte[] canonical = Wire.canonicalRegistration(instanceId, backendName, gameAddress, generation, keyId);
        byte[] signed = new byte[hello.nonce.length + canonical.length];
        System.arraycopy(hello.nonce, 0, signed, 0, hello.nonce.length);
        System.arraycopy(canonical, 0, signed, hello.nonce.length, canonical.length);
        byte[] signature = hmac(secret, signed);
        active.writeDirect(Wire.register(instanceId, backendName, gameAddress, generation, keyId, signature));
        byte[] registeredFrame = FrameCodec.read(active.in);
        epoch = Wire.decodeRegistered(registeredFrame);
        long pongTimeoutMillis = pongTimeoutMillis(heartbeatMillis);
        active.socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, pongTimeoutMillis));
        active.lastPongNanos = System.nanoTime();
        active.startWriter();
        active.heartbeatTask = timer.scheduleAtFixedRate(new Runnable() {
                @Override public void run() {
                    if (session.get() != active || closed.get()) return;
                    try {
                        if (!active.enqueueControlQuietly(Wire.empty(Wire.HEARTBEAT),
                                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(heartbeatMillis))) {
                            active.close(new IOException("heartbeat writer is backpressured"));
                        }
                    } catch (IOException invalid) { active.close(invalid); }
                }
        }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        active.livenessTask = timer.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                if (session.get() != active || closed.get()) return;
                long age = System.nanoTime() - active.lastPongNanos;
                if (age >= TimeUnit.MILLISECONDS.toNanos(pongTimeoutMillis)) active.close(new IOException("proxy heartbeat timed out"));
            }
        }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        synchronized (stateMonitor) {
            if (closed.get() || active.isClosed()) throw new IOException("client closed during backend registration");
            session.set(active);
            registered = true;
            stateMonitor.notifyAll();
        }
        while (!active.isClosed()) {
            byte[] frame = FrameCodec.read(active.in);
            handleFrame(active, frame);
        }
    }

    private void handleFrame(final Session active, byte[] frame) throws IOException {
        if (frame.length == 0) throw new IOException("empty frame");
        int type = frame[0] & 0xff;
        if (type == Wire.PONG) {
            Wire.validateEmpty(frame, Wire.PONG);
            active.lastPongNanos = System.nanoTime();
            return;
        }
        if (type == MessageCodec.MESSAGE) {
            handleMessage(active, MessageCodec.decodeMessage(frame));
            return;
        }
        if (type == MessageCodec.RESPONSE) {
            active.handleResponse(MessageCodec.decodeResponse(frame));
            return;
        }
        if (type == Wire.GOODBYE) {
            Wire.validateEmpty(frame, Wire.GOODBYE);
            throw new IOException("proxy closed channel");
        }
        throw new IOException("unexpected proxy frame type: " + type);
    }

    private void handleMessage(final Session active, MessageCodec.IncomingMessage incoming) throws IOException {
        if (active != session.get()) return;
        final long operationId = incoming.operationId;
        final long timeoutMillis = incoming.timeoutMillis;
        long now = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        final long deadlineNanos = Long.MAX_VALUE - now < timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
        final Message message = incoming.message;
        if (message.kind() == MessageKind.EVENT) {
            if (!Endpoint.backend(backendName).equals(message.target())) {
                sendError(active, operationId, MessagingException.Code.REJECTED, "event target does not match this backend", deadlineNanos);
                return;
            }
            if (expired(deadlineNanos)) return;
            SendResult result = localMessaging.receiveEvent(message);
            enqueueResponse(active, MessageCodec.sendResult(operationId, result), deadlineNanos);
            return;
        }
        if (message.kind() == MessageKind.REQUEST) {
            if (!Endpoint.backend(backendName).equals(message.target())) {
                sendError(active, operationId, MessagingException.Code.REJECTED, "request target does not match this backend", deadlineNanos);
                return;
            }
            if (expired(deadlineNanos)) return;
            long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            localMessaging.receiveRequest(message, Duration.ofMillis(remainingMillis)).whenComplete((reply, failure) -> {
                if (active != session.get() || active.isClosed()) return;
                if (failure != null) {
                    Throwable cause = unwrap(failure);
                    MessagingException.Code code = cause instanceof MessagingException
                            ? ((MessagingException) cause).code() : MessagingException.Code.HANDLER_FAILED;
                    sendError(active, operationId, code, safeDetail(cause), deadlineNanos);
                } else {
                    try { enqueueResponse(active, MessageCodec.reply(operationId, reply), deadlineNanos); }
                    catch (IOException invalid) { active.close(invalid); }
                }
            });
            return;
        }
        sendError(active, operationId, MessagingException.Code.REJECTED, "reply messages are not accepted as requests", deadlineNanos);
    }

    private static boolean expired(long deadlineNanos) {
        return isExpired(deadlineNanos);
    }

    private void sendError(Session active, long operationId, MessagingException.Code code, String detail,
                           long deadlineNanos) {
        try { enqueueResponse(active, MessageCodec.error(operationId, code, detail), deadlineNanos); }
        catch (IOException failure) { active.close(failure); }
    }

    private void enqueueResponse(Session active, byte[] frame, long deadlineNanos) {
        if (isExpired(deadlineNanos)) return;
        if (!active.enqueue(frame, deadlineNanos, null, null)) {
            synchronized (active.writeLock) {
                if (active == session.get() && !active.isClosed() && !isExpired(deadlineNanos)) {
                    active.close(new IOException("could not queue messaging response"));
                }
            }
        }
    }

    private static boolean isExpired(long deadlineNanos) {
        return deadlineNanos - System.nanoTime() <= 0;
    }

    private void setRegistered(boolean value) {
        synchronized (stateMonitor) {
            registered = value && !closed.get();
            stateMonitor.notifyAll();
        }
    }

    private static Message withEndpointSource(Message message, Endpoint source) {
        return new Message(message.id(), message.kind(), message.channel(), source,
                message.target(), message.replyTo(), message.payload());
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static long pongTimeoutMillis(long heartbeatMillis) {
        long triple = heartbeatMillis > Long.MAX_VALUE / 3L ? Long.MAX_VALUE : heartbeatMillis * 3L;
        return Math.max(15_000L, triple);
    }

    private static void validateIdentity(String value, String label) {
        if (value == null || value.trim().isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > Wire.MAX_STRING_BYTES) {
            throw new IllegalArgumentException(label + " is empty or too long");
        }
    }

    private static long durationMillis(Duration duration) {
        if (duration == null) throw new NullPointerException("timeout");
        long millis;
        try { millis = duration.toMillis(); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("timeout is too large", overflow); }
        if (millis < MessageCodec.MIN_TIMEOUT_MILLIS || millis > MessageCodec.MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("timeout must be in 1..60000 milliseconds");
        }
        return millis;
    }

    private static String safeDetail(Throwable failure) {
        String detail = failure == null ? "message failed" : failure.getMessage();
        if (detail == null || detail.isEmpty()) detail = failure == null ? "message failed" : failure.getClass().getSimpleName();
        byte[] bytes = detail.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 1024) return detail;
        int end = 1024;
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static MessagingException notConnected() {
        return new MessagingException(MessagingException.Code.NOT_CONNECTED, "backend channel is disconnected");
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) return failure.getCause();
        if (failure instanceof ExecutionException && failure.getCause() != null) return failure.getCause();
        return failure;
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(error);
        return future;
    }

    private static ThreadFactory namedFactory(final String prefix) {
        return new ThreadFactory() {
            private final AtomicLong count = new AtomicLong();
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, prefix + "-" + count.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private final class PendingOperation {
        final Session owner;
        final long operationId;
        final MessageCodec.Response.Type expected;
        final Message message;
        final long deadlineNanos;
        final CompletableFuture<MessageCodec.Response> future = new CompletableFuture<MessageCodec.Response>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) cancelPending();
                return cancelled;
            }
        };
        final AtomicBoolean done = new AtomicBoolean();
        volatile ScheduledFuture<?> timeoutTask;
        volatile WriteTask writeTask;

        PendingOperation(Session owner, long operationId, MessageCodec.Response.Type expected, Message message, long deadlineNanos) {
            this.owner = owner;
            this.operationId = operationId;
            this.expected = expected;
            this.message = message;
            this.deadlineNanos = deadlineNanos;
        }

        void startTimeout() {
            try {
                timeoutTask = timer.schedule(new Runnable() {
                    @Override public void run() {
                        fail(new MessagingException(MessagingException.Code.TIMED_OUT, "backend message timed out"));
                    }
                }, Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException stopped) {
                fail(new MessagingException(MessagingException.Code.CLOSED, "backend messaging is closed", stopped));
            }
            if (done.get() && timeoutTask != null) timeoutTask.cancel(false);
        }

        boolean isDone() { return done.get(); }

        void accept(MessageCodec.Response response) {
            if (done.get()) return;
            if (response.type == MessageCodec.Response.Type.ERROR) {
                fail(new MessagingException(response.errorCode, response.detail));
                return;
            }
            if (response.type != expected) {
                fail(new MessagingException(MessagingException.Code.PROTOCOL_ERROR, "unexpected response type"));
                return;
            }
            if (expected == MessageCodec.Response.Type.REPLY && !validReply(message, response.message)) {
                fail(new MessagingException(MessagingException.Code.PROTOCOL_ERROR, "reply does not match request"));
                return;
            }
            if (expected == MessageCodec.Response.Type.PUBLISH
                    && !message.id().equals(response.publishResult.messageId())) {
                fail(new MessagingException(MessagingException.Code.PROTOCOL_ERROR, "publish receipt has the wrong message ID"));
                return;
            }
            finish(response, null);
        }

        void fail(Throwable failure) { finish(null, failure); }

        void finish(MessageCodec.Response response, Throwable failure) {
            WriteTask currentWrite = writeTask;
            boolean blockedWriteExpired = failure instanceof MessagingException
                    && ((MessagingException) failure).code() == MessagingException.Code.TIMED_OUT
                    && currentWrite != null && currentWrite.writeInProgress && !currentWrite.writeCompleted;
            if (!cleanup()) return;
            if (blockedWriteExpired) owner.close(failure);
            if (failure == null) future.complete(response);
            else future.completeExceptionally(failure);
        }

        private void cancelPending() { cleanup(); }

        private boolean cleanup() {
            synchronized (owner.writeLock) {
                if (!done.compareAndSet(false, true)) return false;
                if (owner.pending.remove(operationId, this)) owner.requestSlots.release();
                ScheduledFuture<?> timeout = timeoutTask;
                if (timeout != null) timeout.cancel(false);
                WriteTask queued = writeTask;
                if (queued != null) owner.remove(queued);
            }
            return true;
        }
    }

    private static boolean validReply(Message request, Message reply) {
        return reply != null && reply.kind() == MessageKind.REPLY
                && request.channel().equals(reply.channel())
                && request.id().equals(reply.replyTo())
                && request.target().equals(reply.source())
                && request.source().equals(reply.target());
    }

    private static final class WriteTask {
        final byte[] frame;
        final long deadlineNanos;
        final PendingOperation pending;
        final CompletableFuture<Void> written;
        final Message message;
        final long operationId;
        final long originalTimeoutMillis;
        volatile boolean started;
        volatile boolean writeInProgress;
        volatile boolean writeCompleted;
        boolean accounted;
        volatile ScheduledFuture<?> expiryTask;
        long expiryGeneration;
        WriteTask(byte[] frame, long deadlineNanos, PendingOperation pending, CompletableFuture<Void> written) {
            this(frame, deadlineNanos, pending, written, null, 0L, 0L);
        }
        WriteTask(byte[] frame, long deadlineNanos, PendingOperation pending, CompletableFuture<Void> written,
                  Message message, long operationId, long originalTimeoutMillis) {
            this.frame = frame;
            this.deadlineNanos = deadlineNanos;
            this.pending = pending;
            this.written = written;
            this.message = message;
            this.operationId = operationId;
            this.originalTimeoutMillis = originalTimeoutMillis;
        }
    }

    private final class Session {
        final Socket socket;
        final Object writeLock = new Object();
        final java.util.concurrent.LinkedBlockingDeque<WriteTask> writeQueue = new java.util.concurrent.LinkedBlockingDeque<WriteTask>();
        final AtomicInteger queuedCount = new AtomicInteger();
        final AtomicLong queuedBytes = new AtomicLong();
        final AtomicLong nextOperationId = new AtomicLong(1L);
        final Semaphore requestSlots = new Semaphore(MAX_PENDING_REQUESTS);
        final Map<Long, PendingOperation> pending = new ConcurrentHashMap<Long, PendingOperation>();
        volatile DataInputStream in;
        volatile DataOutputStream out;
        volatile java.util.concurrent.ScheduledFuture<?> heartbeatTask;
        volatile java.util.concurrent.ScheduledFuture<?> livenessTask;
        volatile long lastPongNanos;
        volatile Thread writerThread;
        private final AtomicBoolean ended = new AtomicBoolean();

        Session(Socket socket) { this.socket = socket; }

        void initialize() throws IOException {
            this.in = new DataInputStream(socket.getInputStream());
            this.out = new DataOutputStream(socket.getOutputStream());
        }

        synchronized void writeDirect(byte[] frame) throws IOException {
            if (isClosed()) throw new IOException("connection closed");
            FrameCodec.write(out, frame);
        }

        void startWriter() {
            Thread writer = new Thread(new Runnable() {
                @Override public void run() { writerLoop(); }
            }, "backend-channel-writer-" + instanceId);
            writer.setDaemon(true);
            writerThread = writer;
            writer.start();
        }

        boolean enqueueControlQuietly(byte[] frame, long deadlineNanos) {
            return enqueue(frame, deadlineNanos, null, new CompletableFuture<Void>());
        }

        boolean enqueue(byte[] frame, long deadlineNanos, PendingOperation operation, CompletableFuture<Void> written) {
            return enqueue(frame, deadlineNanos, operation, written, null, 0L, 0L);
        }

        boolean enqueue(byte[] frame, long deadlineNanos, PendingOperation operation, CompletableFuture<Void> written,
                        Message message, long operationId, long originalTimeoutMillis) {
            if (frame == null || frame.length < 1 || frame.length > Wire.MAX_FRAME_BYTES || isClosed()) return false;
            WriteTask task = new WriteTask(frame, deadlineNanos, operation, written,
                    message, operationId, originalTimeoutMillis);
            synchronized (writeLock) {
                if (isClosed() || (operation != null && operation.isDone()) || isExpired(deadlineNanos)) return false;
                if (queuedCount.get() >= MAX_WRITER_QUEUE || queuedBytes.get() + frame.length > MAX_WRITER_QUEUE_BYTES) return false;
                if (!writeQueue.offerLast(task)) return false;
                queuedCount.incrementAndGet();
                queuedBytes.addAndGet(frame.length);
                task.accounted = true;
                if (operation != null) operation.writeTask = task;
                try {
                    final long generation = ++task.expiryGeneration;
                    task.expiryTask = timer.schedule(() -> expireWriteTask(task, generation),
                            Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (RejectedExecutionException stopped) {
                    writeQueue.remove(task);
                    releaseLocked(task);
                    return false;
                }
                writeLock.notifyAll();
            }
            return true;
        }

        private void expireWriteTask(WriteTask task, long generation) {
            boolean closeForBlockedWrite = false;
            boolean removed = false;
            synchronized (writeLock) {
                if (!task.accounted || task.expiryGeneration != generation || task.writeCompleted) return;
                if (writeQueue.remove(task)) {
                    releaseLocked(task);
                    removed = true;
                } else if (task.writeInProgress) {
                    closeForBlockedWrite = true;
                }
            }
            MessagingException timeout = new MessagingException(MessagingException.Code.TIMED_OUT,
                    "backend frame expired before socket write completed");
            if (task.pending != null && (removed || closeForBlockedWrite)) task.pending.fail(timeout);
            if (task.written != null && removed) task.written.completeExceptionally(timeout);
            if (closeForBlockedWrite) close(timeout);
        }

        private void writerLoop() {
            try {
                while (true) {
                    WriteTask task;
                    boolean canWrite;
                    synchronized (writeLock) {
                        while (writeQueue.isEmpty() && !isClosed()) writeLock.wait();
                        if (isClosed()) break;
                        task = writeQueue.pollFirst();
                        if (task == null) continue;
                        task.started = true;
                        canWrite = !isExpired(task.deadlineNanos)
                                && (task.pending == null || !task.pending.isDone());
                    }
                    if (!canWrite) {
                        if (task.pending != null && !task.pending.isDone()) {
                            task.pending.fail(isExpired(task.deadlineNanos)
                                    ? new MessagingException(MessagingException.Code.TIMED_OUT, "backend message expired before socket write")
                                    : notConnected());
                        }
                        if (task.written != null) task.written.completeExceptionally(new IOException("control frame expired or connection closed"));
                        release(task);
                        continue;
                    }
                    try {
                        byte[] frame = task.frame;
                        if (task.message != null) {
                            long remaining = TimeUnit.NANOSECONDS.toMillis(task.deadlineNanos - System.nanoTime());
                            if (remaining <= 0) {
                                if (task.pending != null) task.pending.fail(new MessagingException(
                                        MessagingException.Code.TIMED_OUT, "backend message expired before socket write"));
                                continue;
                            }
                            remaining = Math.min(task.originalTimeoutMillis, Math.min(MessageCodec.MAX_TIMEOUT_MILLIS, remaining));
                            frame = MessageCodec.message(task.operationId, remaining, task.message);
                        }
                        boolean startWrite;
                        boolean expiredBeforeWrite;
                        synchronized (writeLock) {
                            expiredBeforeWrite = isExpired(task.deadlineNanos);
                            startWrite = !isClosed() && !expiredBeforeWrite
                                    && (task.pending == null || !task.pending.isDone());
                            if (startWrite) {
                                task.writeInProgress = true;
                                ScheduledFuture<?> queueExpiry = task.expiryTask;
                                if (queueExpiry != null) queueExpiry.cancel(false);
                                try {
                                    final long generation = ++task.expiryGeneration;
                                    task.expiryTask = timer.schedule(() -> expireWriteTask(task, generation),
                                            WRITE_COMPLETION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                                } catch (RejectedExecutionException stopped) {
                                    task.writeInProgress = false;
                                    startWrite = false;
                                }
                            }
                        }
                        if (!startWrite) {
                            if (task.pending != null && !task.pending.isDone()) task.pending.fail(expiredBeforeWrite
                                    ? new MessagingException(MessagingException.Code.TIMED_OUT,
                                            "backend message expired before socket write") : notConnected());
                            if (task.written != null) task.written.completeExceptionally(
                                    new IOException("control frame expired or connection closed"));
                            continue;
                        }
                        FrameCodec.write(out, frame);
                        task.writeCompleted = true;
                        task.writeInProgress = false;
                        if (task.written != null) task.written.complete(null);
                    } catch (IOException failure) {
                        task.writeInProgress = false;
                        if (task.written != null) task.written.completeExceptionally(failure);
                        if (task.pending != null) task.pending.fail(notConnected());
                        close(failure);
                    } finally {
                        release(task);
                    }
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } finally {
                WriteTask task;
                while ((task = writeQueue.pollFirst()) != null) {
                    if (task.written != null) task.written.completeExceptionally(new IOException("connection closed"));
                    release(task);
                }
            }
        }

        private void release(WriteTask task) {
            synchronized (writeLock) {
                releaseLocked(task);
            }
        }

        private void releaseLocked(WriteTask task) {
            if (!task.accounted) return;
            task.accounted = false;
            task.expiryGeneration++;
            queuedCount.decrementAndGet();
            queuedBytes.addAndGet(-task.frame.length);
            ScheduledFuture<?> expiry = task.expiryTask;
            if (expiry != null) expiry.cancel(false);
            if (task.pending != null && task.pending.writeTask == task) task.pending.writeTask = null;
            writeLock.notifyAll();
        }

        void remove(WriteTask task) {
            synchronized (writeLock) {
                if (writeQueue.remove(task)) {
                    releaseLocked(task);
                }
            }
        }

        void handleResponse(MessageCodec.Response response) {
            PendingOperation operation = pending.get(response.operationId);
            if (operation != null) operation.accept(response);
        }

        void awaitWriter(CompletableFuture<Void> written, long millis) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            try { written.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (Exception doneOrExpired) { /* Close remains bounded even when the peer is slow. */ }
        }

        boolean isClosed() { return ended.get() || socket.isClosed(); }

        void close(Throwable cause) {
            if (!ended.compareAndSet(false, true)) return;
            java.util.concurrent.ScheduledFuture<?> heart = heartbeatTask;
            if (heart != null) heart.cancel(false);
            java.util.concurrent.ScheduledFuture<?> live = livenessTask;
            if (live != null) live.cancel(false);
            try { socket.close(); } catch (IOException ignored) { }
            Thread writer = writerThread;
            if (writer != null) writer.interrupt();
            Throwable error;
            if (cause instanceof MessagingException) error = cause;
            else error = new MessagingException(MessagingException.Code.NOT_CONNECTED,
                    "backend channel connection closed", cause);
            for (PendingOperation operation : pending.values()) operation.fail(error);
        }
    }
}

package dev.moonbridge.backendchannel;

import dev.moonbridge.messaging.Endpoint;
import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.MessageKind;
import dev.moonbridge.messaging.Messaging;
import dev.moonbridge.messaging.MessagingException;
import dev.moonbridge.messaging.PublishResult;
import dev.moonbridge.messaging.SendResult;
import dev.moonbridge.messaging.internal.LocalMessaging;
import dev.moonbridge.messaging.protocol.MessageCodec;

import static dev.moonbridge.backendchannel.Failures.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

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
    private final AtomicReference<ClientSession> session = new AtomicReference<ClientSession>();
    private volatile ClientSession connecting;
    private final ThreadPoolExecutor handlerExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(MAX_HANDLER_QUEUE), namedFactory("backend-channel-handler"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(2, namedFactory("backend-channel-timer"));
    private final LocalMessaging localMessaging;
    private final InboundDispatcher inbound;
    private final Object stateMonitor = new Object();
    private volatile boolean registered;
    private volatile long epoch;
    private volatile UUID proxyEpoch = new UUID(0L, 0L);
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
        this.inbound = new InboundDispatcher(backendName, localMessaging, new Supplier<ClientSession>() {
            @Override public ClientSession get() { return session.get(); }
        });
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
    public UUID getProxyEpoch() { return proxyEpoch; }

    @Override public void close() {
        final ClientSession active;
        final ClientSession inProgress;
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
        ClientSession active = activeSession();
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
        ClientSession active = activeSession();
        if (active == null) return failed(notConnected());
        return mapOperation(sendOperation(active, authenticated, timeoutMillis, MessageCodec.Response.Type.REPLY),
                response -> response.message, failure -> { throw new CompletionException(failure); });
    }

    @Override public CompletionStage<PublishResult> publish(Message event) {
        if (event == null || event.kind() != MessageKind.EVENT || event.target() != null) {
            return failed(new MessagingException(MessagingException.Code.REJECTED, "publish requires an untargeted event"));
        }
        Message authenticated = withLocalSource(event);
        ClientSession active = activeSession();
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

    private CompletableFuture<MessageCodec.Response> sendOperation(ClientSession active, Message message,
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

    private ClientSession activeSession() {
        ClientSession active = session.get();
        return registered && active != null && !active.isClosed() ? active : null;
    }

    private void reconnectLoop() {
        long pause = reconnectMinMillis;
        while (!closed.get()) {
            ClientSession active = null;
            try {
                Socket socket = new Socket();
                active = new ClientSession(socket, timer, instanceId);
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

    private void runSession(final ClientSession active) throws Exception {
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
        Wire.RegisteredIdentity registration = Wire.decodeRegisteredIdentity(registeredFrame);
        epoch = registration.epoch;
        proxyEpoch = registration.proxyEpoch;
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
            inbound.handleFrame(active, frame);
        }
    }


    private void setRegistered(boolean value) {
        synchronized (stateMonitor) {
            registered = value && !closed.get();
            stateMonitor.notifyAll();
        }
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
}

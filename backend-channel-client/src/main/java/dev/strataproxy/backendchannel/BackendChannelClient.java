package dev.strataproxy.backendchannel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Java 8-compatible reconnecting client for StrataProxy backend control channels. */
public final class BackendChannelClient implements AutoCloseable {
    public static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 5000L;
    public static final int MAX_PENDING_REQUESTS = 128;
    public static final int MAX_HANDLER_QUEUE = 128;

    private final String host, instanceId, backendName, gameAddress, generation, keyId;
    private final int port;
    private final byte[] secret;
    private final long heartbeatMillis, requestTimeoutMillis, reconnectMinMillis, reconnectMaxMillis;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final AtomicInteger activeInbound = new AtomicInteger();
    private final AtomicReference<Session> session = new AtomicReference<Session>();
    private volatile Session connecting;
    private final Map<String, BackendChannelHandler> handlers = new ConcurrentHashMap<String, BackendChannelHandler>();
    private final Map<Long, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<Long, CompletableFuture<byte[]>>();
    private final ThreadPoolExecutor handlerExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(MAX_HANDLER_QUEUE), namedFactory("backend-channel-handler"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(2, namedFactory("backend-channel-timer"));
    private final Object stateMonitor = new Object();
    private final Object pendingLock = new Object();
    private volatile boolean registered;
    private volatile long epoch;
    private final Thread connectionThread;

    public BackendChannelClient(String host, int port, String instanceId, String backendName, String gameAddress,
                                String generation, String keyId, byte[] secret) {
        this(host, port, instanceId, backendName, gameAddress, generation, keyId, secret,
                10_000L, DEFAULT_REQUEST_TIMEOUT_MILLIS, 500L, 10_000L);
    }

    public BackendChannelClient(String host, int port, String instanceId, String backendName, String gameAddress,
                                String generation, String keyId, byte[] secret, long heartbeatMillis,
                                long requestTimeoutMillis, long reconnectMinMillis, long reconnectMaxMillis) {
        if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("host is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range");
        if (secret == null || secret.length < 32) throw new IllegalArgumentException("secret must contain at least 32 bytes");
        if (heartbeatMillis < 1000L || requestTimeoutMillis < 1L || reconnectMinMillis < 1L || reconnectMaxMillis < reconnectMinMillis) {
            throw new IllegalArgumentException("invalid timing configuration");
        }
        validateIdentity(instanceId, "instanceId"); validateIdentity(backendName, "backendName");
        validateIdentity(gameAddress, "gameAddress"); validateIdentity(generation, "generation"); validateIdentity(keyId, "keyId");
        this.host = host; this.port = port; this.instanceId = instanceId; this.backendName = backendName;
        this.gameAddress = gameAddress; this.generation = generation; this.keyId = keyId;
        this.secret = Arrays.copyOf(secret, secret.length); this.heartbeatMillis = heartbeatMillis;
        this.requestTimeoutMillis = requestTimeoutMillis; this.reconnectMinMillis = reconnectMinMillis;
        this.reconnectMaxMillis = reconnectMaxMillis;
        this.timer.setRemoveOnCancelPolicy(true);
        this.connectionThread = new Thread(new Runnable() { public void run() { reconnectLoop(); } }, "backend-channel-connection");
        this.connectionThread.setDaemon(true);
        this.connectionThread.start();
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

    /** Installs one handler for a channel. Closing the returned handle unregisters it. */
    public AutoCloseable registerHandler(String channel, BackendChannelHandler handler) {
        validateChannel(channel);
        if (handler == null) throw new IllegalArgumentException("handler is required");
        if (handlers.putIfAbsent(channel, handler) != null) throw new IllegalStateException("handler already registered: " + channel);
        final BackendChannelHandler installed = handler;
        return new AutoCloseable() {
            public void close() { handlers.remove(channel, installed); }
        };
    }

    /** Sends a one-way message. Fails immediately if disconnected or backpressured. */
    public void send(String channel, byte[] payload) throws IOException {
        validateChannel(channel);
        if (payload == null || payload.length > Wire.MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("payload length out of bounds");
        Session active = session.get();
        if (!registered || active == null) throw new IOException("backend channel is disconnected");
        active.write(Wire.message(channel, 0L, payload));
    }

    /** Sends a request using the configured default timeout. */
    public CompletableFuture<byte[]> request(String channel, byte[] payload) {
        return request(channel, payload, requestTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** Sends a request. The returned future fails on timeout, disconnect, or non-OK response. */
    public CompletableFuture<byte[]> request(String channel, byte[] payload, long timeout, TimeUnit unit) {
        validateChannel(channel);
        if (payload == null || payload.length > Wire.MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("payload length out of bounds");
        if (timeout < 1) throw new IllegalArgumentException("timeout must be positive");
        Session active = session.get();
        if (!registered || active == null) return failed(new IOException("backend channel is disconnected"));
        if (pending.size() >= MAX_PENDING_REQUESTS) return failed(new IOException("too many pending requests"));
        long id = nextRequestId.getAndIncrement();
        if (id <= 0) return failed(new IOException("request ID space exhausted"));
        final CompletableFuture<byte[]> result = new CompletableFuture<byte[]>();
        synchronized (pendingLock) {
            if (pending.size() >= MAX_PENDING_REQUESTS) return failed(new IOException("too many pending requests"));
            if (pending.putIfAbsent(id, result) != null) return failed(new IOException("request ID collision"));
        }
        try {
            active.write(Wire.message(channel, id, payload));
        } catch (IOException ex) {
            pending.remove(id, result);
            result.completeExceptionally(ex);
            return result;
        }
        final ScheduledFuture<?> timeoutTask;
        try {
            timeoutTask = timer.schedule(new Runnable() {
                public void run() { result.completeExceptionally(new TimeoutException("backend request timed out")); }
            }, timeout, unit);
        } catch (RejectedExecutionException stopped) {
            pending.remove(id, result);
            result.completeExceptionally(stopped);
            return result;
        }
        result.whenComplete((value, error) -> {
            pending.remove(id, result);
            timeoutTask.cancel(false);
        });
        return result;
    }

    public long getEpoch() { return epoch; }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Session active = session.getAndSet(null);
        Session inProgress = connecting;
        connecting = null;
        registered = false;
        synchronized (stateMonitor) { stateMonitor.notifyAll(); }
        if (active != null) {
            try { active.write(Wire.empty(Wire.GOODBYE)); } catch (IOException ignored) { }
            active.close();
        }
        if (inProgress != null && inProgress != active) inProgress.close();
        connectionThread.interrupt();
        handlerExecutor.shutdownNow();
        timer.shutdownNow();
        failPending(new IOException("backend channel client closed"));
        Arrays.fill(secret, (byte) 0);
    }

    private void reconnectLoop() {
        long pause = reconnectMinMillis;
        while (!closed.get()) {
            Session active = null;
            try {
                Socket socket = new Socket();
                active = new Session(socket);
                connecting = active;
                if (closed.get()) { active.close(); break; }
                socket.connect(new InetSocketAddress(host, port), 5000);
                active.initialize();
                runSession(active);
                pause = reconnectMinMillis;
            } catch (InterruptedException interrupted) {
                if (closed.get()) break;
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Connection details are deliberately not logged because protocol errors can include peer data.
            } finally {
                Session prior = session.getAndSet(null);
                connecting = null;
                if (prior != null) prior.close();
                if (active != null) active.close();
                setRegistered(false);
                failPending(new IOException("backend channel disconnected"));
            }
            if (!closed.get()) {
                try { Thread.sleep(pause); } catch (InterruptedException e) { if (closed.get()) break; }
                pause = Math.min(reconnectMaxMillis, pause > reconnectMaxMillis / 2 ? reconnectMaxMillis : pause * 2);
            }
        }
    }

    private void runSession(Session active) throws Exception {
        active.socket.setSoTimeout(5000);
        byte[] helloFrame = FrameCodec.read(active.in);
        Wire.Hello hello = Wire.decodeHello(helloFrame);
        if (hello.version != Wire.VERSION) throw new IOException("unsupported proxy protocol version");
        byte[] canonical = Wire.canonicalRegistration(instanceId, backendName, gameAddress, generation, keyId);
        byte[] signed = new byte[hello.nonce.length + canonical.length];
        System.arraycopy(hello.nonce, 0, signed, 0, hello.nonce.length);
        System.arraycopy(canonical, 0, signed, hello.nonce.length, canonical.length);
        byte[] signature = hmac(secret, signed);
        active.write(Wire.register(instanceId, backendName, gameAddress, generation, keyId, signature));
        byte[] registeredFrame = FrameCodec.read(active.in);
        epoch = Wire.decodeRegistered(registeredFrame);
        long pongTimeoutMillis = pongTimeoutMillis(heartbeatMillis);
        active.socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, pongTimeoutMillis));
        active.lastPongNanos = System.nanoTime();
        session.set(active);
        setRegistered(true);
        active.heartbeatTask = timer.scheduleAtFixedRate(new Runnable() {
            public void run() {
                if (session.get() != active || closed.get()) return;
                try { active.write(Wire.empty(Wire.HEARTBEAT)); }
                catch (IOException failure) { active.close(); }
            }
        }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        active.livenessTask = timer.scheduleAtFixedRate(new Runnable() {
            public void run() {
                if (session.get() != active || closed.get()) return;
                long age = System.nanoTime() - active.lastPongNanos;
                if (age >= TimeUnit.MILLISECONDS.toNanos(pongTimeoutMillis)) active.close();
            }
        }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        while (!closed.get() && !active.socket.isClosed()) {
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
        if (type == Wire.MESSAGE) {
            final Wire.Message message = Wire.decodeMessage(frame);
            final BackendChannelHandler handler = handlers.get(message.channel);
            if (message.requestId == 0 && handler == null) return;
            if (activeInbound.incrementAndGet() > MAX_PENDING_REQUESTS) {
                activeInbound.decrementAndGet();
                if (message.requestId != 0) respond(active, message.requestId, Wire.STATUS_ERROR, new byte[0]);
                return;
            }
            try {
                handlerExecutor.execute(new Runnable() {
                    public void run() {
                        if (handler == null) {
                            respond(active, message.requestId, Wire.STATUS_NO_HANDLER, new byte[0]);
                            activeInbound.decrementAndGet();
                            return;
                        }
                        try {
                            java.util.concurrent.CompletionStage<byte[]> stage = handler.handle(message.payload);
                            if (stage == null) {
                                respond(active, message.requestId, Wire.STATUS_ERROR, new byte[0]);
                                activeInbound.decrementAndGet();
                                return;
                            }
                            stage.whenComplete((value, error) -> {
                                try {
                                    if (message.requestId == 0) return;
                                    if (error != null) respond(active, message.requestId, Wire.STATUS_ERROR, errorPayload(error));
                                    else respond(active, message.requestId, Wire.STATUS_OK, value == null ? new byte[0] : value);
                                } finally { activeInbound.decrementAndGet(); }
                            });
                        } catch (Throwable error) {
                            try {
                                if (message.requestId != 0) respond(active, message.requestId, Wire.STATUS_ERROR, errorPayload(error));
                            } finally { activeInbound.decrementAndGet(); }
                        }
                    }
                });
            } catch (RejectedExecutionException full) {
                activeInbound.decrementAndGet();
                if (message.requestId != 0) respond(active, message.requestId, Wire.STATUS_ERROR, new byte[0]);
            }
            return;
        }
        if (type == Wire.GOODBYE) { Wire.validateEmpty(frame, Wire.GOODBYE); throw new IOException("proxy closed channel"); }
        if (type == Wire.RESPONSE) {
            Wire.Response response = Wire.decodeResponse(frame);
            CompletableFuture<byte[]> future = pending.get(response.requestId);
            if (future != null) {
                if (response.status == Wire.STATUS_OK) future.complete(response.payload);
                else future.completeExceptionally(new ChannelResponseException(response.status, response.payload));
            }
            return;
        }
        throw new IOException("unexpected proxy frame type: " + type);
    }

    private void respond(Session active, long requestId, int status, byte[] payload) {
        if (requestId == 0 || session.get() != active) return;
        try {
            active.write(Wire.response(requestId, status, payload));
        } catch (Exception ignored) { active.close(); }
    }

    private void setRegistered(boolean value) {
        registered = value;
        synchronized (stateMonitor) { stateMonitor.notifyAll(); }
    }

    private void failPending(IOException cause) {
        for (CompletableFuture<byte[]> future : pending.values()) future.completeExceptionally(cause);
        pending.clear();
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

    private static void validateChannel(String channel) {
        validateIdentity(channel, "channel");
        if (!channel.matches("[a-z0-9][a-z0-9_.-]{0,63}:[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("channel must match namespace:name using lowercase ASCII letters, digits, '.', '_' or '-'");
        }
    }

    private static byte[] errorPayload(Throwable error) {
        String message = error.getMessage();
        if (message == null) message = error.getClass().getSimpleName();
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        return bytes.length <= Wire.MAX_PAYLOAD_BYTES ? bytes : Arrays.copyOf(bytes, Wire.MAX_PAYLOAD_BYTES);
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(error);
        return future;
    }

    private static ThreadFactory namedFactory(final String prefix) {
        return new ThreadFactory() {
            private final AtomicLong count = new AtomicLong();
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, prefix + "-" + count.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    public static final class ChannelResponseException extends IOException {
        private final int status;
        private final byte[] payload;
        ChannelResponseException(int status, byte[] payload) {
            super("backend channel response status: " + status);
            this.status = status;
            this.payload = payload;
        }
        public int getStatus() { return status; }
        public byte[] getPayload() { return Arrays.copyOf(payload, payload.length); }
    }

    private static final class Session {
        final Socket socket;
        volatile DataInputStream in;
        volatile DataOutputStream out;
        volatile java.util.concurrent.ScheduledFuture<?> heartbeatTask;
        volatile java.util.concurrent.ScheduledFuture<?> livenessTask;
        volatile long lastPongNanos;
        Session(Socket socket) { this.socket = socket; }
        void initialize() throws IOException {
            this.in = new DataInputStream(socket.getInputStream());
            this.out = new DataOutputStream(socket.getOutputStream());
        }
        synchronized void write(byte[] frame) throws IOException {
            if (socket.isClosed()) throw new IOException("connection closed");
            FrameCodec.write(out, frame);
        }
        void close() {
            java.util.concurrent.ScheduledFuture<?> task = heartbeatTask;
            if (task != null) task.cancel(false);
            java.util.concurrent.ScheduledFuture<?> liveness = livenessTask;
            if (liveness != null) liveness.cancel(false);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}

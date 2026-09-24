package dev.strataproxy.agent;

import dev.strataproxy.backend.api.BackendMessage;
import dev.strataproxy.backend.api.BackendMessageRegistration;
import dev.strataproxy.backend.api.DeliveryMode;
import dev.strataproxy.backend.internal.ChannelFrame;
import dev.strataproxy.backend.internal.ChannelWire;
import dev.strataproxy.plugin.service.ChannelPublishResult;
import dev.strataproxy.plugin.service.ProxyChannelService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** In-memory, bounded broker for authenticated backend-agent channel sessions. */
public final class BackendMessageBroker implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendMessageBroker.class);
    private static final int MAX_UNACKNOWLEDGED_PER_BACKEND = 256;
    private static final long IDEMPOTENCY_TTL_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private final Map<String, Endpoint> endpoints = new HashMap<>();
    private final Map<String, Publication> publications = new LinkedHashMap<>(4096, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Publication> eldest) { return size() > 4096; }
    };
    private final Map<String, CopyOnWriteArrayList<Consumer<BackendMessage>>> proxySubscribers = new HashMap<>();
    private final ThreadPoolExecutor callbacks = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1024), runnable -> new Thread(runnable, "strataproxy-agent-callback"),
            new ThreadPoolExecutor.AbortPolicy());
    private boolean closed;

    /** One live transport for a registered backend instance. */
    public interface Session {
        boolean offer(ChannelFrame frame);
        int remainingCapacity();
        void close();
    }

    /** Returns a proxy-plugin scoped publisher and subscriber. */
    public ProxyChannelService forPlugin(String pluginId) {
        var source = "proxy:" + pluginId;
        return new ProxyChannelService() {
            @Override
            public java.util.concurrent.CompletionStage<ChannelPublishResult> publish(
                    String channel, byte[] payload, String correlationId, String idempotencyKey, DeliveryMode mode) {
                return CompletableFuture.completedFuture(BackendMessageBroker.this.publish(source, null, channel, payload, correlationId, idempotencyKey, mode));
            }

            @Override
            public java.util.concurrent.CompletionStage<ChannelPublishResult> publishTo(
                    String backendName, String channel, byte[] payload, String correlationId, String idempotencyKey, DeliveryMode mode) {
                return CompletableFuture.completedFuture(BackendMessageBroker.this.publish(source, backendName, channel, payload, correlationId, idempotencyKey, mode));
            }

            @Override
            public BackendMessageRegistration subscribe(String channel, Consumer<BackendMessage> listener) {
                return BackendMessageBroker.this.subscribe(channel, listener);
            }
        };
    }

    public synchronized boolean attach(String name, String instanceId, Session session) {
        if (closed) return false;
        var previous = endpoints.get(name);
        if (previous != null && !previous.instanceId.equals(instanceId)) {
            previous.close();
            previous = null;
        }
        var endpoint = previous == null ? new Endpoint(instanceId) : previous;
        if (endpoint.session != null && endpoint.session != session) endpoint.session.close();
        endpoint.session = session;
        endpoints.put(name, endpoint);
        return true;
    }

    public synchronized void detach(String name, String instanceId, Session session) {
        var endpoint = endpoints.get(name);
        if (endpoint != null && endpoint.instanceId.equals(instanceId) && endpoint.session == session) {
            endpoint.session = null;
        }
    }

    public synchronized void remove(String name, String instanceId) {
        var endpoint = endpoints.get(name);
        if (endpoint != null && endpoint.instanceId.equals(instanceId)) {
            endpoints.remove(name);
            endpoint.close();
        }
    }

    public synchronized void subscribe(String name, String instanceId, Session session, String channel) {
        if (!validChannel(channel)) return;
        var endpoint = endpoints.get(name);
        if (!current(endpoint, instanceId, session)) return;
        endpoint.channels.add(channel);
        if (endpoint.session != null) {
            for (var message : endpoint.pending.values()) {
                if (channel.equals(message.channel)) endpoint.session.offer(message);
            }
        }
    }

    public synchronized void unsubscribe(String name, String instanceId, Session session, String channel) {
        var endpoint = endpoints.get(name);
        if (current(endpoint, instanceId, session)) {
            endpoint.channels.remove(channel);
            endpoint.pending.values().removeIf(message -> channel.equals(message.channel));
        }
    }

    public synchronized void acknowledge(String name, String instanceId, Session session, String messageId) {
        var endpoint = endpoints.get(name);
        if (current(endpoint, instanceId, session)) endpoint.pending.remove(messageId);
    }

    public synchronized ChannelPublishResult publishFromBackend(String name, String instanceId, Session session, ChannelFrame frame) {
        var endpoint = endpoints.get(name);
        if (!current(endpoint, instanceId, session)) return ChannelPublishResult.failure("not_connected");
        DeliveryMode mode;
        try {
            mode = DeliveryMode.valueOf(frame.mode);
        } catch (IllegalArgumentException exception) {
            return ChannelPublishResult.failure("invalid_mode");
        }
        return publish("backend:" + name + ":" + instanceId, null, frame.channel,
                frame.payload, frame.correlationId, frame.idempotencyKey, mode);
    }

    /** Retries unacknowledged messages on the currently subscribed live stream. */
    public synchronized void retryPending() {
        if (closed) return;
        for (var endpoint : endpoints.values()) {
            if (endpoint.session == null) continue;
            for (var message : endpoint.pending.values()) {
                if (endpoint.channels.contains(message.channel) && !endpoint.session.offer(message)) break;
            }
        }
    }

    private static boolean current(Endpoint endpoint, String instanceId, Session session) {
        return endpoint != null && endpoint.instanceId.equals(instanceId) && endpoint.session == session;
    }

    private synchronized ChannelPublishResult publish(String source, String target, String channel, byte[] payload,
                                                     String correlationId, String idempotencyKey, DeliveryMode mode) {
        if (closed) return ChannelPublishResult.failure("broker_closed");
        if (!validChannel(channel) || payload == null || payload.length > ChannelWire.MAX_PAYLOAD_BYTES || mode == null
                || correlationId == null || correlationId.length() > 256
                || idempotencyKey == null || (idempotencyKey.length() > 128)
                || (!idempotencyKey.isEmpty() && !validToken(idempotencyKey))) {
            return ChannelPublishResult.failure("invalid_message");
        }
        var publicationKey = idempotencyKey.isEmpty() ? null : source + '\u0000' + idempotencyKey;
        byte[] digest = null;
        if (publicationKey != null) {
            publications.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue().createdAt > IDEMPOTENCY_TTL_MILLIS);
            digest = digest(payload);
            var existing = publications.get(publicationKey);
            if (existing != null) {
                if (existing.matches(target, channel, correlationId, mode, digest)) return ChannelPublishResult.accepted(existing.messageId);
                return ChannelPublishResult.failure("idempotency_conflict");
            }
        }
        var recipients = new ArrayList<Endpoint>();
        for (var entry : endpoints.entrySet()) {
            if ((target == null || target.equals(entry.getKey()))
                    && entry.getValue().channels.contains(channel)
                    && !source.equals("backend:" + entry.getKey() + ":" + entry.getValue().instanceId)) {
                recipients.add(entry.getValue());
            }
        }
        if (target != null && recipients.isEmpty()) return ChannelPublishResult.failure("backend_not_subscribed");
        var listeners = proxySubscribers.get(channel);
        var callbackCapacity = callbacks.getQueue().remainingCapacity()
                + Math.max(0, callbacks.getPoolSize() - callbacks.getActiveCount());
        if (listeners != null && callbackCapacity < listeners.size()) {
            return ChannelPublishResult.failure("callback_queue_full");
        }
        for (var recipient : recipients) {
            if (mode == DeliveryMode.RELIABLE && recipient.pending.size() >= MAX_UNACKNOWLEDGED_PER_BACKEND) {
                return ChannelPublishResult.failure("reliable_window_full");
            }
            if (recipient.session != null && recipient.session.remainingCapacity() == 0) {
                return ChannelPublishResult.failure("recipient_queue_full");
            }
        }
        var messageId = UUID.randomUUID().toString();
        if (publicationKey != null) publications.put(publicationKey,
                new Publication(messageId, target, channel, correlationId, mode, digest, System.currentTimeMillis()));
        var message = new BackendMessage(messageId, correlationId, source, channel, payload);
        var wire = new ChannelFrame(ChannelFrame.MESSAGE, "", messageId, correlationId, source, channel,
                mode.name(), "", payload);
        for (var recipient : recipients) {
            if (mode == DeliveryMode.RELIABLE) recipient.pending.put(messageId, wire);
            if (recipient.session != null) recipient.session.offer(wire);
        }
        if (listeners != null) {
            for (var listener : listeners) {
                try {
                    callbacks.execute(() -> {
                        try { listener.accept(message); }
                        catch (RuntimeException exception) { LOGGER.warn("Proxy channel listener failed", exception); }
                    });
                } catch (java.util.concurrent.RejectedExecutionException exception) {
                    LOGGER.warn("Proxy channel callback queue became full for message {}", messageId);
                }
            }
        }
        return ChannelPublishResult.accepted(messageId);
    }

    private synchronized BackendMessageRegistration subscribe(String channel, Consumer<BackendMessage> listener) {
        if (closed || !validChannel(channel) || listener == null) throw new IllegalArgumentException("invalid channel subscription");
        var subscribers = proxySubscribers.computeIfAbsent(channel, ignored -> new CopyOnWriteArrayList<>());
        subscribers.add(listener);
        return () -> subscribers.remove(listener);
    }

    private static boolean validChannel(String channel) {
        return validToken(channel, 128);
    }

    private static boolean validToken(String token) {
        return validToken(token, 128);
    }

    private static boolean validToken(String token, int maxLength) {
        if (token == null || token.isEmpty() || token.length() > maxLength) return false;
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (!((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9') || value == '_' || value == '.'
                    || value == ':' || value == '-')) return false;
        }
        return true;
    }

    private static byte[] digest(byte[] payload) {
        try { return MessageDigest.getInstance("SHA-256").digest(payload); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record Publication(String messageId, String target, String channel, String correlationId,
                               DeliveryMode mode, byte[] digest, long createdAt) {
        private boolean matches(String expectedTarget, String expectedChannel, String expectedCorrelationId,
                                DeliveryMode expectedMode, byte[] expectedDigest) {
            return java.util.Objects.equals(target, expectedTarget) && channel.equals(expectedChannel)
                    && correlationId.equals(expectedCorrelationId) && mode == expectedMode && Arrays.equals(digest, expectedDigest);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        endpoints.values().forEach(Endpoint::close);
        endpoints.clear();
        publications.clear();
        proxySubscribers.clear();
        callbacks.shutdownNow();
    }

    private static final class Endpoint {
        private final String instanceId;
        private final Set<String> channels = new HashSet<>();
        private final Map<String, ChannelFrame> pending = new LinkedHashMap<>();
        private Session session;

        private Endpoint(String instanceId) { this.instanceId = instanceId; }
        private void close() { if (session != null) session.close(); }
    }
}

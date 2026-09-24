package dev.strataproxy.bukkit;

import dev.strataproxy.backend.api.BackendAgentApi;
import dev.strataproxy.backend.api.BackendMessage;
import dev.strataproxy.backend.api.BackendMessageListener;
import dev.strataproxy.backend.api.BackendMessageRegistration;
import dev.strataproxy.backend.api.BackendMessageResult;
import dev.strataproxy.backend.api.DeliveryMode;
import dev.strataproxy.backend.internal.ChannelFrame;
import dev.strataproxy.backend.internal.ChannelWire;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.net.Socket;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Java 8 backend-agent stream; it never uses a Minecraft player connection. */
final class BackendChannelClient implements BackendAgentApi, AutoCloseable {
    private final Plugin plugin;
    private final StrataProxyBackendAgentPlugin.BackendAgentClient authentication;
    private final Map<String, List<BackendMessageListener>> listeners = new ConcurrentHashMap<String, List<BackendMessageListener>>();
    private final Map<String, CompletableFuture<BackendMessageResult>> pending = new ConcurrentHashMap<String, CompletableFuture<BackendMessageResult>>();
    private final ArrayBlockingQueue<ChannelFrame> outgoing = new ArrayBlockingQueue<ChannelFrame>(256);
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "strataproxy-agent-channel-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, Boolean> delivered = new LinkedHashMap<String, Boolean>(4096, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 4096; }
    };
    private final HashSet<String> delivering = new HashSet<String>();
    private volatile Socket socket;

    BackendChannelClient(Plugin plugin, StrataProxyBackendAgentPlugin.BackendAgentClient authentication) {
        this.plugin = plugin;
        this.authentication = authentication;
    }

    void start() {
        if (!started.compareAndSet(false, true)) return;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { reconnectLoop(); }
        }, "strataproxy-agent-channel");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public CompletionStage<BackendMessageResult> publish(String channel, byte[] payload, String correlationId,
                                                          String idempotencyKey, DeliveryMode mode) {
        if (closed.get() || socket == null) return CompletableFuture.completedFuture(BackendMessageResult.failure("not_connected"));
        if (!validChannel(channel) || payload == null || payload.length > ChannelWire.MAX_PAYLOAD_BYTES || mode == null
                || correlationId == null || correlationId.length() > 256 || idempotencyKey == null
                || idempotencyKey.length() > 128 || (!idempotencyKey.isEmpty() && !idempotencyKey.matches("[A-Za-z0-9_.:-]+"))) {
            return CompletableFuture.completedFuture(BackendMessageResult.failure("invalid_message"));
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<BackendMessageResult> result = new CompletableFuture<BackendMessageResult>();
        if (pending.size() >= 256) return CompletableFuture.completedFuture(BackendMessageResult.failure("pending_requests_full"));
        pending.put(requestId, result);
        if (!outgoing.offer(new ChannelFrame(ChannelFrame.PUBLISH, requestId, "", correlationId, idempotencyKey, "", channel,
                mode.name(), "", payload))) {
            pending.remove(requestId);
            result.complete(BackendMessageResult.failure("outgoing_queue_full"));
        } else {
            final java.util.concurrent.ScheduledFuture<?> timeout = timeouts.schedule(new Runnable() {
                @Override public void run() {
                    if (pending.remove(requestId, result)) result.complete(BackendMessageResult.failure("request_timeout"));
                }
            }, 10, TimeUnit.SECONDS);
            result.whenComplete((ignored, failure) -> timeout.cancel(false));
        }
        return result;
    }

    @Override
    public BackendMessageRegistration listen(String channel, BackendMessageListener listener) {
        if (closed.get() || !validChannel(channel) || listener == null) throw new IllegalArgumentException("invalid channel subscription");
        List<BackendMessageListener> existing = listeners.putIfAbsent(channel, new CopyOnWriteArrayList<BackendMessageListener>());
        List<BackendMessageListener> channelListeners = existing == null ? listeners.get(channel) : existing;
        channelListeners.add(listener);
        if (channelListeners.size() == 1 && socket != null) control(ChannelFrame.SUBSCRIBE, channel);
        return new BackendMessageRegistration() {
            private final AtomicBoolean removed = new AtomicBoolean();
            @Override public void close() {
                if (!removed.compareAndSet(false, true)) return;
                channelListeners.remove(listener);
                if (channelListeners.isEmpty() && listeners.remove(channel, channelListeners) && socket != null) {
                    control(ChannelFrame.UNSUBSCRIBE, channel);
                }
            }
        };
    }

    private void reconnectLoop() {
        while (!closed.get()) {
            try {
                Socket connected = authentication.openStream();
                socket = connected;
                for (String channel : listeners.keySet()) control(ChannelFrame.SUBSCRIBE, channel);
                Thread writer = new Thread(new Runnable() {
                    @Override public void run() { writeLoop(connected); }
                }, "strataproxy-agent-channel-write");
                writer.setDaemon(true);
                writer.start();
                ChannelFrame frame;
                while (!closed.get() && (frame = ChannelWire.read(connected.getInputStream())) != null) {
                    if (frame.type == ChannelFrame.MESSAGE) deliver(frame);
                    else if (frame.type == ChannelFrame.RESULT) {
                        CompletableFuture<BackendMessageResult> waiting = pending.remove(frame.requestId);
                        if (waiting != null) waiting.complete(new BackendMessageResult(
                                frame.payload.length > 0 && frame.payload[0] == 1, frame.outcome, frame.messageId));
                    } else throw new IOException("unsupported proxy frame type");
                }
            } catch (Exception exception) {
                if (!closed.get()) plugin.getLogger().warning("StrataProxy channel disconnected: " + exception.getMessage());
            } finally {
                Socket previous = socket;
                socket = null;
                if (previous != null) try { previous.close(); } catch (IOException ignored) { }
                outgoing.clear();
                for (CompletableFuture<BackendMessageResult> waiting : pending.values()) {
                    waiting.complete(BackendMessageResult.failure("connection_lost"));
                }
                pending.clear();
            }
            if (!closed.get()) try { Thread.sleep(2_000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
    }

    private void writeLoop(Socket connected) {
        try {
            while (!closed.get() && connected == socket) {
                ChannelFrame frame = outgoing.poll(1, TimeUnit.SECONDS);
                if (frame != null) ChannelWire.write(connected.getOutputStream(), frame);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            try { connected.close(); } catch (IOException ignored) { }
        }
    }

    private void deliver(final ChannelFrame frame) {
        final boolean reliable = DeliveryMode.RELIABLE.name().equals(frame.mode);
        if (reliable) {
            synchronized (delivered) {
                if (delivered.containsKey(frame.messageId)) {
                    acknowledge(frame.messageId);
                    return;
                }
                if (!delivering.add(frame.messageId)) return;
            }
        }
        final List<BackendMessageListener> channelListeners = listeners.get(frame.channel);
        if (channelListeners == null || channelListeners.isEmpty()) {
            if (reliable) synchronized (delivered) { delivering.remove(frame.messageId); }
            return;
        }
        final BackendMessage message = new BackendMessage(frame.messageId, frame.correlationId, frame.source,
                frame.channel, frame.payload);
        try { plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                boolean success = true;
                for (BackendMessageListener listener : channelListeners) {
                    try { listener.onMessage(message); }
                    catch (RuntimeException exception) {
                        success = false;
                        plugin.getLogger().warning("StrataProxy channel listener failed: " + exception.getMessage());
                    }
                }
                if (reliable) {
                    synchronized (delivered) {
                        delivering.remove(frame.messageId);
                        if (success) delivered.put(frame.messageId, Boolean.TRUE);
                    }
                    if (success) acknowledge(frame.messageId);
                }
            }
        }); } catch (RuntimeException exception) {
            if (reliable) synchronized (delivered) { delivering.remove(frame.messageId); }
            plugin.getLogger().warning("StrataProxy channel dispatch failed: " + exception.getMessage());
        }
    }

    private void acknowledge(String messageId) {
        if (!outgoing.offer(new ChannelFrame(ChannelFrame.ACK, "", messageId, "", "", "", "", "", null))) {
            Socket connected = socket;
            if (connected != null) try { connected.close(); } catch (IOException ignored) { }
        }
    }

    private void control(byte type, String channel) {
        if (!outgoing.offer(new ChannelFrame(type, "", "", "", "", channel, "", "", null))) {
            Socket connected = socket;
            if (connected != null) try { connected.close(); } catch (IOException ignored) { }
        }
    }

    private static boolean validChannel(String channel) {
        if (channel == null || channel.isEmpty() || channel.length() > 128) return false;
        for (int index = 0; index < channel.length(); index++) {
            char value = channel.charAt(index);
            if (!((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9') || value == '_' || value == '.'
                    || value == ':' || value == '-')) return false;
        }
        return true;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Socket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
        for (CompletableFuture<BackendMessageResult> waiting : pending.values()) waiting.complete(BackendMessageResult.failure("client_closed"));
        pending.clear();
        timeouts.shutdownNow();
        listeners.clear();
    }
}

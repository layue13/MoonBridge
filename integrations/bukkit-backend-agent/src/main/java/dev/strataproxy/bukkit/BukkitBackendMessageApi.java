package dev.strataproxy.bukkit;

import dev.strataproxy.backend.api.BackendAgentApi;
import dev.strataproxy.backend.api.BackendMessage;
import dev.strataproxy.backend.api.BackendMessageListener;
import dev.strataproxy.backend.api.BackendMessageRegistration;
import dev.strataproxy.backend.api.BackendMessageResult;
import dev.strataproxy.backend.api.BackendPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bukkit adapter for the platform-neutral backend-agent messaging contract. */
final class BukkitBackendMessageApi implements BackendAgentApi, PluginMessageListener, AutoCloseable {
    private final Plugin plugin;
    private final Map<String, List<BackendMessageListener>> listeners = new ConcurrentHashMap<String, List<BackendMessageListener>>();
    private final Map<String, Boolean> incomingChannels = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, Boolean> outgoingChannels = new ConcurrentHashMap<String, Boolean>();
    private final AtomicBoolean closed = new AtomicBoolean();

    BukkitBackendMessageApi(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletionStage<BackendMessageResult> send(final BackendPlayer player, final String channel, byte[] payload) {
        final CompletableFuture<BackendMessageResult> result = new CompletableFuture<BackendMessageResult>();
        if (closed.get()) {
            result.complete(BackendMessageResult.failure("agent_stopped"));
            return result;
        }
        if (player == null || !validChannel(channel)) {
            result.complete(BackendMessageResult.failure("invalid_request"));
            return result;
        }
        final byte[] message = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        Runnable write = new Runnable() {
            @Override
            public void run() {
                if (closed.get()) {
                    result.complete(BackendMessageResult.failure("agent_stopped"));
                    return;
                }
                Player target = findPlayer(player);
                if (target == null || !target.isOnline()) {
                    result.complete(BackendMessageResult.failure("player_not_found"));
                    return;
                }
                try {
                    ensureOutgoing(channel);
                    target.sendPluginMessage(plugin, channel, message);
                    result.complete(BackendMessageResult.acceptedForWrite());
                } catch (RuntimeException exception) {
                    result.complete(BackendMessageResult.failure("write_failed"));
                }
            }
        };
        runOnPrimaryThread(write);
        return result;
    }

    @Override
    public BackendMessageRegistration listen(final String channel, final BackendMessageListener listener) {
        if (closed.get()) throw new IllegalStateException("agent is stopped");
        if (!validChannel(channel) || listener == null) throw new IllegalArgumentException("channel and listener are required");
        final String normalized = channel.trim();
        final List<BackendMessageListener> channelListeners = listeners.computeIfAbsent(
                normalized, ignored -> new CopyOnWriteArrayList<BackendMessageListener>());
        channelListeners.add(listener);
        runOnPrimaryThread(new Runnable() {
            @Override public void run() { ensureIncoming(normalized); }
        });
        return new BackendMessageRegistration() {
            private final AtomicBoolean removed = new AtomicBoolean();

            @Override
            public void close() {
                if (!removed.compareAndSet(false, true)) return;
                channelListeners.remove(listener);
                if (channelListeners.isEmpty()) {
                    listeners.remove(normalized, channelListeners);
                    runOnPrimaryThread(new Runnable() {
                        @Override public void run() { removeIncoming(normalized); }
                    });
                }
            }
        };
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] payload) {
        List<BackendMessageListener> channelListeners = listeners.get(channel);
        if (channelListeners == null || channelListeners.isEmpty() || player == null) return;
        BackendMessage message = new BackendMessage(
                new BackendPlayer(player.getUniqueId(), player.getName()), channel, payload);
        for (BackendMessageListener listener : channelListeners) {
            try {
                listener.onMessage(message);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("StrataProxy backend message listener failed on " + channel + ": " + exception.getMessage());
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Runnable cleanup = new Runnable() {
            @Override
            public void run() {
                for (String channel : incomingChannels.keySet()) {
                    plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, channel, BukkitBackendMessageApi.this);
                }
                for (String channel : outgoingChannels.keySet()) {
                    plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, channel);
                }
                incomingChannels.clear();
                outgoingChannels.clear();
                listeners.clear();
            }
        };
        runOnPrimaryThread(cleanup);
    }

    private void ensureIncoming(String channel) {
        if (incomingChannels.putIfAbsent(channel, Boolean.TRUE) == null) {
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, channel, this);
        }
    }

    private void removeIncoming(String channel) {
        if (incomingChannels.remove(channel) != null) {
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, channel, this);
        }
    }

    private void ensureOutgoing(String channel) {
        if (outgoingChannels.putIfAbsent(channel, Boolean.TRUE) == null) {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, channel);
        }
    }

    private Player findPlayer(BackendPlayer player) {
        UUID uniqueId = player.uniqueId();
        if (uniqueId != null) {
            Player found = Bukkit.getPlayer(uniqueId);
            if (found != null) return found;
        }
        return player.name().isEmpty() ? null : Bukkit.getPlayerExact(player.name());
    }

    private void runOnPrimaryThread(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    private static boolean validChannel(String channel) {
        return channel != null && !channel.trim().isEmpty();
    }
}

package dev.strataproxy.bukkit;

import dev.strataproxy.backendchannel.BackendChannelClient;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** One process-level authenticated transport shared by all dependent plugins. */
public final class StrataProxyChannelsPlugin extends JavaPlugin implements Listener {
    private volatile boolean stopping;
    private BackendChannelClient client;
    private OwnedMessagingService service;

    @Override public void onEnable() {
        stopping = false;
        saveDefaultConfig();
        byte[] secret = null;
        try {
            String secretText = getConfig().getString("secret", "");
            if (secretText.startsWith("replace-") || secretText.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("Configure a unique secret of at least 32 UTF-8 bytes");
            }
            secret = secretText.getBytes(StandardCharsets.UTF_8);
            client = new BackendChannelClient(
                    required("proxyHost"), getConfig().getInt("proxyPort", 28081),
                    required("instanceId"), required("backendName"), required("gameAddress"),
                    UUID.randomUUID().toString(), required("keyId"), secret);
            service = new OwnedMessagingService(client::messaging, this::mainThreadExecutor);
            getServer().getServicesManager().register(BukkitMessagingService.class, service, this, ServicePriority.Normal);
            getServer().getPluginManager().registerEvents(this, this);
            getLogger().info("Shared StrataProxy messaging service started; registration runs asynchronously");
        } catch (RuntimeException failure) {
            // Do not include configuration values or authentication peer data.
            getLogger().severe("Cannot start StrataProxy messaging: " + failure.getClass().getSimpleName());
            getServer().getPluginManager().disablePlugin(this);
        } finally {
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }

    private String required(String key) {
        String value = getConfig().getString(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing configuration key: " + key);
        return value;
    }

    private Executor mainThreadExecutor(final Plugin owner) {
        return command -> {
            if (stopping || !owner.isEnabled()) throw new RejectedExecutionException("Message owner is disabled");
            try {
                getServer().getScheduler().runTask(owner, () -> {
                    if (!stopping && owner.isEnabled()) command.run();
                });
            } catch (RuntimeException rejected) {
                // A disable can race the enabled check. Preserve the Executor
                // rejection contract so the dispatcher releases its admission slot.
                throw new RejectedExecutionException("Bukkit scheduler rejected the message owner", rejected);
            }
        };
    }

    @EventHandler public void onPluginDisable(PluginDisableEvent event) {
        OwnedMessagingService active = service;
        if (active != null) active.release(event.getPlugin());
    }

    @Override public void onDisable() {
        stopping = true;
        getServer().getServicesManager().unregisterAll(this);
        if (service != null) { service.close(); service = null; }
        if (client != null) { client.close(); client = null; }
    }
}

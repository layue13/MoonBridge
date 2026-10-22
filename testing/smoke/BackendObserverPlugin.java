package dev.moonbridge.smoke;

import dev.moonbridge.bukkit.BukkitMessagingService;
import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.MessageKind;
import dev.moonbridge.messaging.Messaging;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/** A separate plugin class/loader; no JavaPlugin implementation is shared between owners. */
public final class BackendObserverPlugin extends JavaPlugin {
    private String backendName;
    private int events;
    private String lastEvent = "";

    @Override public void onEnable() {
        backendName = getConfig().getString("backendName", "");
        BukkitMessagingService service = Bukkit.getServicesManager().load(BukkitMessagingService.class);
        if (service == null || backendName.isEmpty()) throw new IllegalStateException("observer configuration unavailable");
        Messaging messaging = service.forPlugin(this);
        messaging.channel("accept:events").subscribe(message -> {
            check(message, MessageKind.EVENT);
            events++;
            lastEvent = message.id().toString();
        });
        messaging.channel("accept:observer").onRequest(message -> {
            check(message, MessageKind.REQUEST);
            String response = "node=" + backendName + ";role=observer;main=true;events=" + events
                    + ";lastEvent=" + lastEvent + ";id=" + message.id();
            return CompletableFuture.completedFuture(response.getBytes(StandardCharsets.UTF_8));
        });
        getLogger().info("ACCEPTANCE_PLUGIN_READY node=" + backendName + " role=observer");
    }

    private void check(Message message, MessageKind kind) {
        if (!Bukkit.isPrimaryThread() || !isEnabled() || message.kind() != kind || message.replyTo() != null
                || Bukkit.getWorlds().stream().anyMatch(world -> !world.getPlayers().isEmpty())) {
            throw new IllegalStateException("observer callback violated thread, player or envelope contract");
        }
    }

    @Override public void onDisable() {
        // Deliberately rely on the host's owner-scope cleanup.
        getLogger().info("ACCEPTANCE_PLUGIN_DISABLED node=" + backendName + " role=observer");
    }
}

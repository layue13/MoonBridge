package dev.moonbridge.smoke;

import dev.moonbridge.bukkit.BackendPlayerSession;
import dev.moonbridge.bukkit.BukkitSessionService;
import java.util.IdentityHashMap;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Real login/Join proof lifecycle acceptance; compiles only against the public Bukkit API. */
public final class EarlySessionAcceptancePlugin extends JavaPlugin implements Listener {
    private BukkitSessionService sessions;
    private final Map<Player, BackendPlayerSession> pending = new IdentityHashMap<>();

    @Override public void onEnable() {
        sessions = getServer().getServicesManager().load(BukkitSessionService.class);
        if (sessions == null) throw new IllegalStateException("Missing shared session service");
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void login(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        try {
            BackendPlayerSession binding = sessions.authenticate(player).orElseThrow(
                    () -> new AssertionError("No early proof"));
            if (sessions.authenticate(player).orElse(null) != binding)
                throw new AssertionError("Repeated authentication changed binding");
            if (sessions.find(player).isPresent()) throw new AssertionError("Provisional session became online");
            if (!binding.getPlayerId().equals(player.getUniqueId()) || binding.getBackendEpoch() <= 0)
                throw new AssertionError("Invalid backend identity");
            Object profile = player.getClass().getMethod("getProfile").invoke(player);
            Object properties = profile.getClass().getMethod("getProperties").invoke(profile);
            Object matches = properties.getClass().getMethod("get", Object.class)
                    .invoke(properties, "moonbridge:session");
            if (((Iterable<?>) matches).iterator().hasNext()) throw new AssertionError("Proof leaked into profile");
            pending.put(player, binding);
            getLogger().info("EARLY_AUTH_PASS player=" + player.getUniqueId() + " join=" + binding.getJoinEpoch());
        } catch (Throwable failure) {
            getLogger().severe("EARLY_AUTH_FAILED " + failure);
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, "Early session acceptance failed");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final BackendPlayerSession expected = pending.remove(player);
        getServer().getScheduler().runTask(this, () -> {
            if (expected == null || sessions.find(player).orElse(null) != expected) {
                getLogger().severe("EARLY_PROMOTION_FAILED");
                player.kickPlayer("Early session promotion failed");
            } else getLogger().info("EARLY_PROMOTION_PASS join=" + expected.getJoinEpoch());
        });
    }

    @EventHandler public void quit(PlayerQuitEvent event) { pending.remove(event.getPlayer()); }
}

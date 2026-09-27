package dev.moonbridge.luckperms;

import dev.moonbridge.api.PluginContext;
import me.lucko.luckperms.common.plugin.bootstrap.LuckPermsBootstrap;
import me.lucko.luckperms.common.plugin.classpath.ClassPathAppender;
import me.lucko.luckperms.common.plugin.logging.PluginLogger;
import me.lucko.luckperms.common.plugin.logging.Slf4jPluginLogger;
import me.lucko.luckperms.common.plugin.scheduler.SchedulerAdapter;
import net.luckperms.api.platform.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

final class MoonBridgeBootstrap implements LuckPermsBootstrap, AutoCloseable {
    private static final String VERSION = "5.5.85-moonbridge";
    private final LuckPermsMoonBridgePlugin plugin;
    private final PluginContext context;
    private final MoonBridgeScheduler scheduler = new MoonBridgeScheduler();
    private final CountDownLatch loaded = new CountDownLatch(1);
    private final CountDownLatch enabled = new CountDownLatch(1);
    private final Instant started = Instant.now();
    private final PluginLogger logger;

    MoonBridgeBootstrap(LuckPermsMoonBridgePlugin plugin, PluginContext context) {
        this.plugin = plugin;
        this.context = context;
        this.logger = new Slf4jPluginLogger(context.logger());
    }

    void markLoaded() { loaded.countDown(); }
    void markEnabled() { enabled.countDown(); }
    @Override public PluginLogger getPluginLogger() { return logger; }
    @Override public SchedulerAdapter getScheduler() { return scheduler; }
    @Override public ClassPathAppender getClassPathAppender() {
        return jar -> {
            try { context.addLibrary(jar); }
            catch (IOException e) { throw new IllegalStateException("LuckPerms dependency is outside its plugin data directory: " + jar, e); }
        };
    }
    @Override public CountDownLatch getLoadLatch() { return loaded; }
    @Override public CountDownLatch getEnableLatch() { return enabled; }
    @Override public String getVersion() { return VERSION; }
    @Override public Instant getStartupTime() { return started; }
    @Override public Platform.Type getType() { return Platform.Type.STANDALONE; }
    @Override public String getServerBrand() { return "MoonBridge"; }
    @Override public String getServerVersion() { return "MoonBridge"; }
    @Override public String getServerName() { return context.settings().get("proxy-id"); }
    @Override public Path getDataDirectory() { return context.dataDirectory(); }
    @Override public Optional<?> getPlayer(UUID uuid) { return plugin.subject(uuid).map(MoonBridgePermissionSubject::player); }
    @Override public Optional<UUID> lookupUniqueId(String username) { return Optional.empty(); }
    @Override public Optional<String> lookupUsername(UUID uuid) {
        return context.players().online().stream().filter(player -> player.identity().playerId().equals(uuid)).map(dev.moonbridge.api.PlayerView::username).findFirst();
    }
    @Override public int getPlayerCount() { return context.players().online().size(); }
    @Override public Collection<String> getPlayerList() { return context.players().online().stream().map(dev.moonbridge.api.PlayerView::username).toList(); }
    @Override public Collection<UUID> getOnlinePlayers() { return context.players().online().stream().map(player -> player.identity().playerId()).toList(); }
    @Override public boolean isPlayerOnline(UUID uuid) { return context.players().online().stream().anyMatch(player -> player.identity().playerId().equals(uuid)); }
    @Override public void close() { scheduler.shutdownScheduler(); scheduler.shutdownExecutor(); }
}

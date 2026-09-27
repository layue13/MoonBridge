package dev.moonbridge.luckperms;

import dev.moonbridge.api.CommandCompletion;
import dev.moonbridge.api.CommandRegistrationOptions;
import dev.moonbridge.api.CommandSource;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.luckperms.bridge.MoonBridgeChat;
import dev.moonbridge.api.permission.PermissionDecision;
import me.lucko.luckperms.common.api.LuckPermsApiProvider;
import me.lucko.luckperms.common.api.ApiRegistrationUtil;
import me.lucko.luckperms.common.cacheddata.CacheMetadata;
import me.lucko.luckperms.common.calculator.CalculatorFactory;
import me.lucko.luckperms.common.calculator.PermissionCalculator;
import me.lucko.luckperms.common.calculator.PermissionCalculatorMonitored;
import me.lucko.luckperms.common.calculator.processor.DirectProcessor;
import me.lucko.luckperms.common.calculator.processor.PermissionProcessor;
import me.lucko.luckperms.common.calculator.processor.RegexProcessor;
import me.lucko.luckperms.common.calculator.processor.SpongeWildcardProcessor;
import me.lucko.luckperms.common.calculator.processor.WildcardProcessor;
import me.lucko.luckperms.common.command.CommandManager;
import me.lucko.luckperms.common.command.utils.ArgumentTokenizer;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.config.generic.adapter.ConfigurateConfigAdapter;
import me.lucko.luckperms.common.config.generic.adapter.ConfigurationAdapter;
import me.lucko.luckperms.common.dependencies.Dependency;
import me.lucko.luckperms.common.event.AbstractEventBus;
import me.lucko.luckperms.common.messaging.MessagingFactory;
import me.lucko.luckperms.common.locale.TranslationManager;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.model.manager.group.StandardGroupManager;
import me.lucko.luckperms.common.model.manager.track.StandardTrackManager;
import me.lucko.luckperms.common.model.manager.user.StandardUserManager;
import me.lucko.luckperms.common.plugin.AbstractLuckPermsPlugin;
import me.lucko.luckperms.common.plugin.util.AbstractConnectionListener;
import me.lucko.luckperms.common.sender.Sender;
import me.lucko.luckperms.common.sender.SenderFactory;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** LuckPerms common-engine integration using MoonBridge's real player and command APIs. */
final class MoonBridgePlatform extends AbstractLuckPermsPlugin {
    private final LuckPermsMoonBridgePlugin owner;
    private final MoonBridgeBootstrap bootstrap;
    private MoonBridgeSenderFactory senderFactory;
    private AbstractConnectionListener connectionListener;
    private CommandManager commandManager;
    private StandardUserManager userManager;
    private StandardGroupManager groupManager;
    private StandardTrackManager trackManager;
    private MoonBridgeContextManager contextManager;
    private volatile boolean apiRegistered;

    MoonBridgePlatform(LuckPermsMoonBridgePlugin owner, MoonBridgeBootstrap bootstrap) {
        this.owner = owner;
        this.bootstrap = bootstrap;
    }

    /** Stop recurring work and finish/cancel HTTP callbacks before disable closes dependency classloaders. */
    void prepareForDisable() {
        bootstrap.getScheduler().shutdownScheduler();
        okhttp3.OkHttpClient client = getHttpClient();
        if (client == null) return;
        client.dispatcher().cancelAll();
        ExecutorService calls = client.dispatcher().executorService();
        calls.shutdown();
        await(calls, 1500, TimeUnit.MILLISECONDS);
        if (!calls.isTerminated()) {
            calls.shutdownNow();
            await(calls, 500, TimeUnit.MILLISECONDS);
        }
        client.connectionPool().evictAll();
    }

    private static void await(ExecutorService service, long timeout, TimeUnit unit) {
        try {
            service.awaitTermination(timeout, unit);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            service.shutdownNow();
        }
    }

    /** Best-effort rollback for a failed load/enable hook, whose normal disable path assumes full initialization. */
    void abortStartup() {
        bootstrap.getScheduler().shutdownScheduler();
        cleanup("LuckPerms web editor", () -> {
            if (getWebEditorStore() != null) getWebEditorStore().sockets().getSockets().forEach(socket -> {
                if (!socket.isClosed()) socket.close();
            });
        });
        cleanup("LuckPerms permission registry", () -> {
            if (getPermissionRegistry() instanceof me.lucko.luckperms.common.treeview.AsyncPermissionRegistry registry) registry.close();
        });
        cleanup("LuckPerms verbose handler", () -> { if (getVerboseHandler() != null) getVerboseHandler().close(); });
        cleanup("LuckPerms extensions", () -> { if (getExtensionManager() != null) getExtensionManager().close(); });
        cleanup("LuckPerms messaging", () -> getMessagingService().ifPresent(me.lucko.luckperms.common.messaging.InternalMessagingService::close));
        cleanup("LuckPerms storage", () -> { if (getStorage() != null) getStorage().shutdown(); });
        cleanup("LuckPerms file watcher", () -> getFileWatcher().ifPresent(me.lucko.luckperms.common.storage.implementation.file.watcher.FileWatcher::close));
        if (apiRegistered) cleanup("LuckPerms API registration", ApiRegistrationUtil::unregisterProvider);
        cleanup("LuckPerms HTTP", () -> {
            okhttp3.OkHttpClient client = getHttpClient();
            if (client != null) {
                client.dispatcher().cancelAll();
                client.dispatcher().executorService().shutdownNow();
                client.connectionPool().evictAll();
            }
        });
        cleanup("LuckPerms sender factory", () -> { if (senderFactory != null) senderFactory.close(); });
        cleanup("LuckPerms dependency manager", () -> { if (getDependencyManager() != null) getDependencyManager().close(); });
        bootstrap.close();
    }

    private void cleanup(String resource, Runnable action) {
        try { action.run(); }
        catch (Throwable failure) { bootstrap.getPluginLogger().warn("Failed to clean up " + resource, failure); }
    }

    @Override public MoonBridgeBootstrap getBootstrap() { return bootstrap; }
    @Override protected void setupSenderFactory() { senderFactory = new MoonBridgeSenderFactory(this); }

    @Override
    protected Set<Dependency> getGlobalDependencies() {
        Set<Dependency> dependencies = super.getGlobalDependencies();
        dependencies.add(Dependency.CONFIGURATE_CORE);
        dependencies.add(Dependency.CONFIGURATE_YAML);
        dependencies.add(Dependency.SNAKEYAML);
        return dependencies;
    }

    @Override protected ConfigurationAdapter provideConfigurationAdapter() {
        return new ConfigAdapter(this, resolveConfig("config.yml"));
    }
    @Override protected void registerPlatformListeners() { connectionListener = new AbstractConnectionListener(this) {}; }
    @Override protected MessagingFactory<?> provideMessagingFactory() {
        return new MessagingFactory<>(this);
    }

    @Override
    protected void registerCommands() {
        commandManager = new CommandManager(this);
        var options = CommandRegistrationOptions.defaults().withConsole(true);
        for (String root : List.of("lp", "luckperms")) {
            owner.context().commands().registerAsync(root, options, invocation -> {
                CommandSource source = invocation.source();
                List<String> args = ArgumentTokenizer.EXECUTE.tokenizeInput(invocation.arguments());
                // LP commands run on its own executor and may synchronously dispatch nested proxy commands
                // (e.g. /lp verbose command). Keep host command workers free while LP finishes the command.
                return commandManager.executeCommand(senderFactory.wrap(source), root, args);
            }, completion -> {
                List<String> args = ArgumentTokenizer.TAB_COMPLETE.tokenizeInput(completion.arguments());
                PlayerView player = completion.player();
                return java.util.concurrent.CompletableFuture.completedFuture(
                        commandManager.tabCompleteCommand(senderFactory.wrap(MoonBridgeChat.source(owner.context(), Optional.of(player))), args));
            });
        }
    }

    @Override protected void setupManagers() {
        userManager = new StandardUserManager(this);
        groupManager = new StandardGroupManager(this);
        trackManager = new StandardTrackManager(this);
    }

    @Override protected CalculatorFactory provideCalculatorFactory() {
        return (queryOptions, sourceMap, metadata) -> {
            List<PermissionProcessor> processors = new ArrayList<>(4);
            processors.add(new DirectProcessor(sourceMap));
            if (getConfiguration().get(ConfigKeys.APPLYING_REGEX)) processors.add(new RegexProcessor(sourceMap));
            if (getConfiguration().get(ConfigKeys.APPLYING_WILDCARDS)) processors.add(new WildcardProcessor(sourceMap));
            if (getConfiguration().get(ConfigKeys.APPLYING_WILDCARDS_SPONGE)) processors.add(new SpongeWildcardProcessor(sourceMap));
            return new PermissionCalculatorMonitored(this, metadata, processors);
        };
    }

    @Override protected void setupContextManager() {
        contextManager = new MoonBridgeContextManager(this, owner.context().settings().get("proxy-id"));
    }
    @Override protected void setupPlatformHooks() { }
    @Override protected AbstractEventBus<?> provideEventBus(LuckPermsApiProvider apiProvider) {
        return new AbstractEventBus<Object>(this, apiProvider) {
            @Override protected Object checkPlugin(Object plugin) {
                if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");
                return plugin;
            }
        };
    }
    @Override protected void registerApiOnPlatform(LuckPerms api) { apiRegistered = true; }
    @Override protected void performFinalSetup() { }

    @Override public Optional<QueryOptions> getQueryOptionsForUser(User user) {
        return owner.subject(user.getUniqueId()).map(subject -> getContextManager().queryOptions(subject.player()));
    }
    @Override public Stream<Sender> getOnlineSenders() {
        Stream<Sender> players = owner.context().players().online().stream()
                .map(view -> senderFactory.wrap(MoonBridgeChat.source(owner.context(), Optional.of(view))));
        return Stream.concat(Stream.of(getConsoleSender()), players);
    }
    @Override public Sender getConsoleSender() {
        return senderFactory.wrap(MoonBridgeChat.source(owner.context(), Optional.empty()));
    }
    @Override public AbstractConnectionListener getConnectionListener() { return connectionListener; }
    void dispatchUserFirstLogin(UUID uniqueId, String username) {
        getEventDispatcher().dispatchUserFirstLogin(uniqueId, username);
    }
    @Override public CommandManager getCommandManager() { return commandManager; }
    @Override public StandardUserManager getUserManager() { return userManager; }
    @Override public StandardGroupManager getGroupManager() { return groupManager; }
    @Override public StandardTrackManager getTrackManager() { return trackManager; }
    @Override public MoonBridgeContextManager getContextManager() { return contextManager; }

    private final class MoonBridgeSenderFactory extends SenderFactory<MoonBridgePlatform, CommandSource> {
        MoonBridgeSenderFactory(MoonBridgePlatform plugin) { super(plugin); }
        @Override protected String getName(CommandSource source) { return source.player().map(PlayerView::username).orElse(Sender.CONSOLE_NAME); }
        @Override protected UUID getUniqueId(CommandSource source) { return source.player().map(player -> player.identity().playerId()).orElse(Sender.CONSOLE_UUID); }
        @Override protected void sendMessage(CommandSource source, net.kyori.adventure.text.Component message) {
            // LuckPerms translation keys are registered in its private Adventure classloader.
            // Resolve them before crossing the JSON boundary; Minecraft cannot translate these keys.
            MoonBridgeChat.send(source, GsonComponentSerializer.gson().serialize(TranslationManager.render(message)));
        }
        @Override protected Tristate getPermissionValue(CommandSource source, String node) {
            if (source.isConsole()) return Tristate.TRUE;
            PlayerView view = source.player().orElseThrow();
            return owner.subject(view.identity().playerId()).filter(subject -> subject.identity().equals(view.identity()))
                    .map(subject -> switch (subject.check(node, dev.moonbridge.api.permission.PermissionContext.empty())) {
                        case ALLOW -> Tristate.TRUE; case DENY -> Tristate.FALSE; case UNDEFINED -> Tristate.UNDEFINED;
                    }).orElse(Tristate.UNDEFINED);
        }
        @Override protected boolean hasPermission(CommandSource source, String node) { return getPermissionValue(source, node).asBoolean(); }
        @Override protected void performCommand(CommandSource source, String command) {
            try {
                boolean handled = owner.context().commands().execute(source, command).toCompletableFuture().join();
                if (!handled) reply(source, "Unknown MoonBridge command: " + command);
            } catch (CompletionException failure) {
                reply(source, "Could not execute proxy command: " + message(failure));
            } catch (Throwable failure) {
                reply(source, "Could not execute proxy command: " + message(failure));
            }
        }
        @Override protected boolean isConsole(CommandSource source) { return source.isConsole(); }
        @Override protected boolean shouldSplitNewlines(CommandSource source) { return true; }
        private void reply(CommandSource source, String text) {
            MoonBridgeChat.send(source, GsonComponentSerializer.gson().serialize(net.kyori.adventure.text.Component.text(text)));
        }
        private String message(Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        }
    }

    private static final class ConfigAdapter extends ConfigurateConfigAdapter implements ConfigurationAdapter {
        ConfigAdapter(MoonBridgePlatform plugin, Path path) { super(plugin, path); }
        @Override protected ConfigurationLoader<? extends ConfigurationNode> createLoader(Path path) {
            return YAMLConfigurationLoader.builder().setPath(path).build();
        }
    }
}

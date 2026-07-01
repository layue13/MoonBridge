package dev.strataproxy.plugin.loader;

import dev.strataproxy.command.DefaultCommandRegistry;
import dev.strataproxy.command.DefaultScheduler;
import dev.strataproxy.command.SimpleEventBus;
import dev.strataproxy.plugin.PluginContext;
import dev.strataproxy.plugin.ProxyPlugin;
import dev.strataproxy.plugin.command.CommandResult;
import dev.strataproxy.plugin.command.CommandSource;
import dev.strataproxy.plugin.command.CommandSpec;
import dev.strataproxy.plugin.service.PlayerService;
import dev.strataproxy.plugin.service.ServerService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PluginManagerTest {
    @Test
    void missingDirectoryLoadsNoPlugins() throws Exception {
        var scheduler = new DefaultScheduler();
        try (var manager = new PluginManager(
                new DefaultCommandRegistry(),
                new SimpleEventBus(),
                emptyPlayers(),
                emptyServers(),
                scheduler,
                Logger.getAnonymousLogger())) {
            var loaded = manager.loadDirectory(Files.createTempDirectory("strataproxy-plugins").resolve("missing"));

            assertTrue(loaded.isEmpty());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void invalidJarInDirectoryIsSkipped() throws Exception {
        var directory = Files.createTempDirectory("strataproxy-plugins");
        try (var output = new JarOutputStream(Files.newOutputStream(directory.resolve("bad.jar")))) {
            // Empty jar: no descriptor and no ServiceLoader provider.
        }
        var scheduler = new DefaultScheduler();
        try (var manager = new PluginManager(
                new DefaultCommandRegistry(),
                new SimpleEventBus(),
                emptyPlayers(),
                emptyServers(),
                scheduler,
                Logger.getAnonymousLogger())) {
            var loaded = manager.loadDirectory(directory);

            assertTrue(loaded.isEmpty());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void descriptorPluginLoadsAndRegistersCommand() throws Exception {
        DescriptorPlugin.loaded.set(0);
        DescriptorPlugin.enabled.set(0);
        DescriptorPlugin.disabled.set(0);
        var directory = Files.createTempDirectory("strataproxy-plugins");
        var pluginJar = directory.resolve("descriptor-plugin.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(pluginJar))) {
            output.putNextEntry(new JarEntry("strataproxy-plugin.properties"));
            output.write(("""
                    id=descriptor-test
                    name=Descriptor Test
                    version=1.0.0
                    main=%s
                    """.formatted(DescriptorPlugin.class.getName())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }

        var commands = new DefaultCommandRegistry();
        var scheduler = new DefaultScheduler();
        try (var manager = new PluginManager(
                commands,
                new SimpleEventBus(),
                emptyPlayers(),
                emptyServers(),
                scheduler,
                Logger.getAnonymousLogger())) {
            var loaded = manager.loadDirectory(directory);

            assertEquals(1, loaded.size());
            assertEquals("descriptor-test", loaded.get(0).id());
            assertEquals(1, DescriptorPlugin.loaded.get());
            assertEquals(1, DescriptorPlugin.enabled.get());
            var result = commands.execute(source(), "/plugintest").toCompletableFuture().join();
            assertTrue(result.handled());
            assertTrue(result.success());
            assertEquals("plugin-ok", result.message());
        } finally {
            scheduler.close();
        }

        assertEquals(1, DescriptorPlugin.disabled.get());
    }

    private static PlayerService emptyPlayers() {
        return new PlayerService() {
            @Override
            public java.util.concurrent.CompletionStage<dev.strataproxy.plugin.service.PlayerTransfer> transfer(String playerName, String targetServer) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        new dev.strataproxy.plugin.service.PlayerTransfer(false, "unavailable", playerName, "", targetServer));
            }

            @Override
            public Optional<dev.strataproxy.plugin.service.PlayerView> find(String playerName) {
                return Optional.empty();
            }

            @Override
            public java.util.Collection<dev.strataproxy.plugin.service.PlayerView> onlinePlayers() {
                return List.of();
            }
        };
    }

    private static ServerService emptyServers() {
        return new ServerService() {
            @Override
            public Optional<dev.strataproxy.plugin.service.ServerView> find(String serverName) {
                return Optional.empty();
            }

            @Override
            public Optional<dev.strataproxy.plugin.service.ServerView> firstWithTag(String tag) {
                return Optional.empty();
            }

            @Override
            public java.util.Collection<dev.strataproxy.plugin.service.ServerView> servers() {
                return List.of();
            }
        };
    }

    private static CommandSource source() {
        return new CommandSource() {
            @Override
            public String name() {
                return "Steve";
            }
        };
    }

    public static final class DescriptorPlugin implements ProxyPlugin {
        private static final AtomicInteger loaded = new AtomicInteger();
        private static final AtomicInteger enabled = new AtomicInteger();
        private static final AtomicInteger disabled = new AtomicInteger();

        @Override
        public void onLoad(PluginContext context) {
            loaded.incrementAndGet();
            context.commands().register(new CommandSpec("plugintest", List.of(), "", "", command ->
                    CompletableFuture.completedFuture(CommandResult.ok("plugin-ok"))));
        }

        @Override
        public void onEnable() {
            enabled.incrementAndGet();
        }

        @Override
        public void onDisable() {
            disabled.incrementAndGet();
        }
    }
}

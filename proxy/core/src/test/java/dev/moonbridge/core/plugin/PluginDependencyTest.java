package dev.moonbridge.core.plugin;

import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.MessageResult;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.Players;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.api.PluginInfo;
import dev.moonbridge.api.ServiceRegistration;
import dev.moonbridge.api.TransferResult;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDependencyTest {
    private static final String SERVICES = "META-INF/services/dev.moonbridge.api.Plugin";

    @TempDir
    Path temporary;

    /** Plugins append to this list, found through the "log" setting, so the test can see lifecycle order. */
    private final String logKey = "moonbridge.test.plugin-log." + UUID.randomUUID();
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private final List<String> propertyKeys = new ArrayList<>(List.of(logKey));

    PluginDependencyTest() {
        System.getProperties().put(logKey, log);
    }

    @AfterEach
    void clearProperties() {
        propertyKeys.forEach(System.getProperties()::remove);
    }

    @Test
    void dependentLoadsAfterItsDependencyAndSeesItsClassesAndServices() throws Exception {
        Path plugins = temporary.resolve("plugins");
        // Filename order would load islands first; the dependency puts economy before it.
        Path economyClasses = jar(plugins.resolve("z-economy.jar"), Map.of(
                "economy/EconomyApi.java", "package economy; public interface EconomyApi { int balance(); }",
                "economy/Main.java", plugin("economy", "context.services().register(EconomyApi.class, () -> 42);", "")),
                descriptor("economy", "economy.Main"), List.of());
        jar(plugins.resolve("a-islands.jar"), Map.of("islands/Main.java", plugin("islands", "",
                        "log().add(\"islands sees \" + context.services().require(economy.EconomyApi.class).balance());")),
                descriptor("islands", "islands.Main", "economy"), List.of(economyClasses));
        jar(plugins.resolve("m-other.jar"), Map.of("other/Main.java", plugin("other", "", """
                        try {
                            Class.forName("economy.EconomyApi", false, getClass().getClassLoader());
                            log().add("other sees economy classes");
                        } catch (ClassNotFoundException expected) {
                            log().add("other cannot see economy classes");
                        }
                        log().add("other finds economy info " + context.plugins().find("economy").isPresent());
                        """)), descriptor("other", "other.Main"), List.of());
        jar(plugins.resolve("b-legacy.jar"), Map.of("legacy/Main.java", plugin("legacy", "", "")),
                Map.of(SERVICES, "legacy.Main\n"), List.of());

        try (var host = host()) {
            host.loadPlugins(plugins, enabled("economy", "islands", "other", "legacy.Main"));
            host.enable();
        }
        assertEquals(List.of(
                "legacy load", "other load", "economy load", "islands load",
                "legacy enable",
                "other enable", "other cannot see economy classes", "other finds economy info true",
                "economy enable",
                "islands enable", "islands sees 42",
                "islands disable", "economy disable", "other disable", "legacy disable"), log);
    }

    @Test
    void pluginsSeeTheDescriptorsOfLoadedPlugins() throws Exception {
        Path plugins = temporary.resolve("plugins");
        String infoKey = property("info");
        jar(plugins.resolve("economy.jar"), Map.of("economy/Main.java", plugin("economy", "", "")),
                descriptor("economy", "economy.Main"), List.of());
        jar(plugins.resolve("islands.jar"), Map.of("islands/Main.java", plugin("islands", "",
                        "System.getProperties().put(\"" + infoKey + "\", List.of(context.plugins().self(), context.plugins().all()));")),
                descriptor("islands", "islands.Main", "economy"), List.of());
        try (var host = host()) {
            host.loadPlugins(plugins, enabled("economy", "islands"));
            host.enable();
            var economy = new PluginInfo("economy", "1.0.0", List.of());
            var islands = new PluginInfo("islands", "1.0.0", List.of("economy"));
            assertEquals(List.of(Optional.of(islands), List.of(economy, islands)), System.getProperties().get(infoKey));
        }
    }

    @Test
    void servicesAreOnlyVisibleToDependentsAndGoAwayWhenUnregistered() throws Exception {
        Path plugins = temporary.resolve("plugins");
        String registrationKey = property("registration");
        String dependentKey = property("dependent");
        String strangerKey = property("stranger");
        jar(plugins.resolve("provider.jar"), Map.of("provider/Main.java", plugin("provider",
                        "System.getProperties().put(\"" + registrationKey
                                + "\", context.services().register(Runnable.class, () -> { }));", "")),
                descriptor("provider", "provider.Main"), List.of());
        jar(plugins.resolve("dependent.jar"), Map.of("dependent/Main.java", plugin("dependent",
                        "System.getProperties().put(\"" + dependentKey + "\", context);", "")),
                descriptor("dependent", "dependent.Main", "provider"), List.of());
        jar(plugins.resolve("stranger.jar"), Map.of("stranger/Main.java", plugin("stranger",
                        "System.getProperties().put(\"" + strangerKey + "\", context);", "")),
                descriptor("stranger", "stranger.Main"), List.of());
        try (var host = host()) {
            host.loadPlugins(plugins, enabled("provider", "dependent", "stranger"));
            host.enable();
            var dependent = (PluginContext) System.getProperties().get(dependentKey);
            var stranger = (PluginContext) System.getProperties().get(strangerKey);
            assertTrue(dependent.services().find(Runnable.class).isPresent());
            assertFalse(stranger.services().find(Runnable.class).isPresent(),
                    "a plugin that did not declare the provider must not reach its services");
            ((ServiceRegistration) System.getProperties().get(registrationKey)).close();
            assertFalse(dependent.services().find(Runnable.class).isPresent());
        }
    }

    @Test
    void secondProviderOfATypeFailsStartup() throws Exception {
        Path plugins = temporary.resolve("plugins");
        for (String id : List.of("first", "second")) {
            jar(plugins.resolve(id + ".jar"), Map.of(id + "/Main.java", plugin(id,
                            "context.services().register(Runnable.class, () -> { });", "")),
                    descriptor(id, id + ".Main"), List.of());
        }
        var failure = loadFailure(plugins, enabled("first", "second"));
        assertTrue(causes(failure).contains("java.lang.Runnable is already provided by plugin first"), causes(failure));
    }

    @Test
    void missingDisabledAndOptionalDependenciesAreReportedPrecisely() throws Exception {
        Path plugins = temporary.resolve("plugins");
        jar(plugins.resolve("islands.jar"), Map.of("islands/Main.java", plugin("islands", "", "")),
                descriptor("islands", "islands.Main", "economy"), List.of());
        assertEquals("Plugin islands requires economy, which is not installed",
                loadFailure(plugins, enabled("islands")).getMessage());

        jar(plugins.resolve("economy.jar"), Map.of("economy/Main.java", plugin("economy", "", "")),
                descriptor("economy", "economy.Main"), List.of());
        assertEquals("Plugin islands requires economy, which is installed but not enabled in plugins.enabled",
                loadFailure(plugins, enabled("islands")).getMessage());

        Path optional = temporary.resolve("optional");
        jar(optional.resolve("chat.jar"), Map.of("chat/Main.java", plugin("chat", "", "")),
                "id: chat\nversion: 1.0.0\nmain: chat.Main\ndependencies:\n  - id: luckperms\n    optional: true\n",
                List.of());
        try (var host = host()) {
            host.loadPlugins(optional, enabled("chat"));
            host.enable();
        }
        assertTrue(log.contains("chat enable"));
    }

    @Test
    void cyclesDuplicateIdsAndDoubleEnablingFailStartup() throws Exception {
        Path cycle = temporary.resolve("cycle");
        jar(cycle.resolve("a.jar"), Map.of("a/Main.java", plugin("a", "", "")), descriptor("a", "a.Main", "b"), List.of());
        jar(cycle.resolve("b.jar"), Map.of("b/Main.java", plugin("b", "", "")), descriptor("b", "b.Main", "a"), List.of());
        assertEquals("Plugin dependency cycle: a -> b -> a", loadFailure(cycle, enabled("a", "b")).getMessage());

        Path duplicate = temporary.resolve("duplicate");
        jar(duplicate.resolve("one.jar"), Map.of("one/Main.java", plugin("one", "", "")), descriptor("same", "one.Main"), List.of());
        jar(duplicate.resolve("two.jar"), Map.of("two/Main.java", plugin("two", "", "")), descriptor("same", "two.Main"), List.of());
        assertEquals("Plugin id same is used by both one.jar and two.jar",
                loadFailure(duplicate, enabled("same")).getMessage());

        Path twice = temporary.resolve("twice");
        jar(twice.resolve("economy.jar"), Map.of("economy/Main.java", plugin("economy", "", "")),
                descriptor("economy", "economy.Main"), List.of());
        assertEquals("Plugin economy is enabled twice, by id and by class name economy.Main",
                loadFailure(twice, enabled("economy", "economy.Main")).getMessage());
    }

    @Test
    void invalidDescriptorsNameTheJarAndTheProblem() throws Exception {
        Path plugins = temporary.resolve("plugins");
        jar(plugins.resolve("broken.jar"), Map.of("broken/Main.java", plugin("broken", "", "")),
                "id: Broken\nversion: 1.0.0\nmain: broken.Main\n", List.of());
        var failure = loadFailure(plugins, enabled("broken.Main"));
        assertTrue(failure.getMessage().startsWith("Invalid META-INF/moonbridge-plugin.yml in broken.jar"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("id must match"), failure.getMessage());
    }

    private IOException loadFailure(Path plugins, Map<String, Map<String, String>> enabled) {
        return assertThrows(IOException.class, () -> {
            try (var host = host()) {
                host.loadPlugins(plugins, enabled);
            }
        });
    }

    private static String causes(Throwable failure) {
        var messages = new ArrayList<String>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.add(String.valueOf(current.getMessage()));
        }
        return String.join(" <- ", messages);
    }

    private String property(String name) {
        String key = "moonbridge.test." + name + "." + UUID.randomUUID();
        propertyKeys.add(key);
        return key;
    }

    private Map<String, Map<String, String>> enabled(String... keys) {
        var enabled = new LinkedHashMap<String, Map<String, String>>();
        for (String key : keys) enabled.put(key, Map.of("log", logKey));
        return enabled;
    }

    private static Map<String, String> descriptor(String id, String main, String... dependencies) {
        var text = new StringBuilder("id: " + id + "\nversion: 1.0.0\nmain: " + main + "\n");
        if (dependencies.length > 0) {
            text.append("dependencies:\n");
            for (String dependency : dependencies) text.append("  - id: ").append(dependency).append('\n');
        }
        return Map.of(PluginDescriptor.RESOURCE, text.toString());
    }

    private Path jar(Path jar, Map<String, String> sources, String descriptor, List<Path> compileAgainst)
            throws IOException {
        return jar(jar, sources, Map.of(PluginDescriptor.RESOURCE, descriptor), compileAgainst);
    }

    private Path jar(Path jar, Map<String, String> sources, Map<String, String> resources, List<Path> compileAgainst)
            throws IOException {
        return TestPluginJars.build(temporary, jar, sources, resources, compileAgainst);
    }

    /** A plugin {@code <pkg>.Main} that logs its lifecycle and runs extra code in onLoad and onEnable. */
    private static String plugin(String pkg, String onLoad, String onEnable) {
        return """
                package %s;
                import dev.moonbridge.api.*;
                import java.util.List;
                public final class Main implements Plugin {
                    private PluginContext context;
                    @SuppressWarnings("unchecked")
                    private List<String> log() {
                        return (List<String>) System.getProperties().get(context.settings().get("log"));
                    }
                    @Override public void onLoad(PluginContext context) {
                        this.context = context;
                        log().add("%s load");
                        %s
                    }
                    @Override public void onEnable() {
                        log().add("%s enable");
                        %s
                    }
                    @Override public void onDisable() { log().add("%s disable"); }
                }
                """.formatted(pkg, pkg, onLoad, pkg, onEnable, pkg);
    }

    private static PluginHost host() {
        return new PluginHost(new InMemoryBackendCatalog(), new Players() {
            @Override public Optional<PlayerView> find(PlayerIdentity identity) { return Optional.empty(); }
            @Override public List<PlayerView> online() { return List.of(); }
            @Override public CompletableFuture<TransferResult> transfer(PlayerIdentity identity, String backend) {
                return CompletableFuture.completedFuture(TransferResult.failed("test"));
            }
            @Override public CompletableFuture<MessageResult> sendMessage(PlayerIdentity identity, Component message) {
                return CompletableFuture.completedFuture(MessageResult.NOT_CONNECTED);
            }
            @Override public CompletableFuture<DisconnectResult> disconnect(PlayerIdentity identity, Component reason) {
                return CompletableFuture.completedFuture(DisconnectResult.NOT_CONNECTED);
            }
        }, Duration.ofSeconds(1));
    }
}

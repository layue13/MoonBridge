package dev.moonbridge.core.plugin;

import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.PluginContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginConstructorsTest {
    public static final class Valid implements Plugin {
        public Valid() { }
        @Override public void onLoad(PluginContext context) { }
    }

    public static final class NoDefaultConstructor implements Plugin {
        public NoDefaultConstructor(String ignored) { }
        @Override public void onLoad(PluginContext context) { }
    }

    public static final class FailingConstructor implements Plugin {
        public FailingConstructor() { throw new IllegalStateException("broken plugin"); }
        @Override public void onLoad(PluginContext context) { }
    }

    @Test
    void createsAPluginThroughItsPublicConstructor() throws Throwable {
        assertInstanceOf(Valid.class, PluginConstructors.newPlugin(Valid.class));
    }

    @Test
    void rejectsClassesThatAreNotPluginsOrLackAPublicNoArgumentConstructor() {
        assertEquals("java.lang.String does not implement dev.moonbridge.api.Plugin",
                assertThrows(IllegalArgumentException.class, () -> PluginConstructors.newPlugin(String.class)).getMessage());
        assertEquals(NoDefaultConstructor.class.getName() + " needs to be public with a public no-argument constructor",
                assertThrows(IllegalArgumentException.class,
                        () -> PluginConstructors.newPlugin(NoDefaultConstructor.class)).getMessage());
    }

    @Test
    void constructorFailuresPropagateUnwrapped() {
        assertEquals("broken plugin", assertThrows(IllegalStateException.class,
                () -> PluginConstructors.newPlugin(FailingConstructor.class)).getMessage());
    }
}

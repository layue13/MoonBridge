package dev.moonbridge.core.plugin;

import dev.moonbridge.api.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Creates a plugin through its public no-argument constructor with a method handle rather than java.lang.reflect.
 * The class is only known by name from the plugin JAR, so it is still loaded by name; the call itself is a method
 * handle the JIT treats like a direct call. The public lookup admits exactly what plugins must offer: a public class
 * with a public no-argument constructor.
 */
final class PluginConstructors {
    private static final MethodType NO_ARGUMENTS = MethodType.methodType(void.class);

    private PluginConstructors() { }

    static Plugin newPlugin(Class<?> type) throws Throwable {
        if (!Plugin.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(type.getName() + " does not implement " + Plugin.class.getName());
        }
        MethodHandle constructor;
        try {
            constructor = MethodHandles.publicLookup().findConstructor(type, NO_ARGUMENTS);
        } catch (NoSuchMethodException | IllegalAccessException missing) {
            throw new IllegalArgumentException(type.getName() + " needs to be public with a public no-argument constructor",
                    missing);
        }
        return (Plugin) constructor.invoke();
    }
}

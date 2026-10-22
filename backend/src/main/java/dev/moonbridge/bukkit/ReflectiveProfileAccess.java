package dev.moonbridge.bukkit;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback for servers without a compiled adapter (neither Uranium nor Spigot/CraftBukkit 1.7.10): finds the GameProfile
 * methods by name. Lookups are resolved once per receiver class and cached, including misses; a missing method means
 * the feature is unavailable on this server, never a guess.
 */
final class ReflectiveProfileAccess implements ProfileAccess {
    static final ReflectiveProfileAccess INSTANCE = new ReflectiveProfileAccess();

    private ReflectiveProfileAccess() { }

    @Override public boolean handles(Object player) { return true; }

    @Override public Object properties(Object player) {
        Object profile = invoke(player, GET_PROFILE);
        if (profile == null) profile = invoke(player, GET_GAME_PROFILE);
        return profile == null ? null : invoke(profile, GET_PROPERTIES);
    }

    @Override public Iterable<?> get(Object properties, String name) {
        Object matches = invoke(properties, MULTIMAP_GET, name);
        return matches instanceof Iterable<?> ? (Iterable<?>) matches : null;
    }

    @Override public boolean remove(Object properties, String name, Object property) {
        return Boolean.TRUE.equals(invoke(properties, MULTIMAP_REMOVE, name, property));
    }

    @Override public Object value(Object property) { return invoke(property, GET_VALUE); }

    @Override public String description() { return "reflection (no compiled adapter for this server)"; }

    /**
     * A public method this class calls reflectively: the GameProfile types differ between Bukkit builds, so the
     * backend does not compile against them. Instances are lookup keys compared by identity.
     */
    private static final class MethodRef {
        final String name;
        final Class<?>[] parameterTypes;

        MethodRef(String name, Class<?>... parameterTypes) {
            this.name = name;
            this.parameterTypes = parameterTypes;
        }
    }

    private static final MethodRef GET_PROFILE = new MethodRef("getProfile");
    private static final MethodRef GET_GAME_PROFILE = new MethodRef("getGameProfile");
    private static final MethodRef GET_PROPERTIES = new MethodRef("getProperties");
    private static final MethodRef GET_VALUE = new MethodRef("getValue");
    private static final MethodRef MULTIMAP_GET = new MethodRef("get", Object.class);
    private static final MethodRef MULTIMAP_REMOVE = new MethodRef("remove", Object.class, Object.class);

    /**
     * Lookups resolved once per receiver class, including misses, so a join does not search methods again. ClassValue
     * keeps the cache from holding a reloaded plugin's classes.
     */
    private static final ClassValue<Map<MethodRef, Optional<Method>>> METHODS =
            new ClassValue<Map<MethodRef, Optional<Method>>>() {
                @Override protected Map<MethodRef, Optional<Method>> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<MethodRef, Optional<Method>>();
                }
            };

    private static Object invoke(Object receiver, MethodRef reference, Object... args) {
        if (receiver == null) return null;
        Map<MethodRef, Optional<Method>> methods = METHODS.get(receiver.getClass());
        Optional<Method> method = methods.get(reference);
        if (method == null) {
            method = lookup(receiver.getClass(), reference);
            methods.put(reference, method);
        }
        if (!method.isPresent()) return null;
        try {
            return method.get().invoke(receiver, args);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private static Optional<Method> lookup(Class<?> type, MethodRef reference) {
        try {
            return Optional.of(type.getMethod(reference.name, reference.parameterTypes));
        } catch (NoSuchMethodException | RuntimeException unavailable) {
            return Optional.empty();
        }
    }
}

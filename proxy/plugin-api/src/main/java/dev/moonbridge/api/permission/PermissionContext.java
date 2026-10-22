package dev.moonbridge.api.permission;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, multi-valued context used when evaluating a permission node. Explicit values override matching defaults;
 * an explicit empty set clears that key from the inherited context.
 */
public record PermissionContext(Map<String, Set<String>> values) {
    private static final PermissionContext EMPTY = new PermissionContext(Map.of());

    public PermissionContext {
        Objects.requireNonNull(values, "values");
        var copy = new LinkedHashMap<String, Set<String>>();
        values.forEach((key, entries) -> {
            requireValue(key, "context key");
            Objects.requireNonNull(entries, "context values");
            var entryCopy = new LinkedHashSet<String>();
            for (String value : entries) {
                requireValue(value, "context value");
                entryCopy.add(value);
            }
            copy.put(key, Set.copyOf(entryCopy));
        });
        values = Map.copyOf(copy);
    }

    public static PermissionContext empty() {
        return EMPTY;
    }

    /** Returns a copy with this value added to the key's values; use the constructor to replace or clear a key. */
    public PermissionContext with(String key, String value) {
        requireValue(key, "context key");
        requireValue(value, "context value");
        var copy = new LinkedHashMap<String, Set<String>>(values);
        var entries = new LinkedHashSet<>(copy.getOrDefault(key, Set.of()));
        entries.add(value);
        copy.put(key, Set.copyOf(entries));
        return new PermissionContext(copy);
    }

    private static void requireValue(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
    }
}

package dev.moonbridge.core.plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Orders plugins so that every plugin comes after the plugins it depends on. */
final class PluginGraph {
    private PluginGraph() { }

    /**
     * Returns {@code items} with dependencies first. Among plugins whose dependencies are already placed, input order
     * wins, so plugins without dependencies keep their JAR filename order. {@code dependsOn} lists ids that are
     * present in {@code items}; a plugin without an id has a null id and no dependencies.
     */
    static <T> List<T> order(List<T> items, Function<T, String> id, Function<T, List<String>> dependsOn) {
        var remaining = new ArrayList<>(items);
        var placed = new HashSet<String>();
        var ordered = new ArrayList<T>(items.size());
        while (!remaining.isEmpty()) {
            T next = null;
            for (T item : remaining) {
                if (placed.containsAll(dependsOn.apply(item))) {
                    next = item;
                    break;
                }
            }
            if (next == null) throw new IllegalStateException("Plugin dependency cycle: " + cycle(remaining, id, dependsOn));
            remaining.remove(next);
            ordered.add(next);
            if (id.apply(next) != null) placed.add(id.apply(next));
        }
        return ordered;
    }

    /** Every plugin reachable through {@code dependsOn}, nearest first, not including {@code start}. */
    static Set<String> reachable(String start, Function<String, List<String>> dependsOn) {
        var seen = new LinkedHashSet<String>();
        var pending = new ArrayList<>(dependsOn.apply(start));
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (seen.add(next)) pending.addAll(dependsOn.apply(next));
        }
        return seen;
    }

    /** Follows unplaced dependencies from any remaining plugin until one repeats: that loop is the cycle. */
    private static <T> String cycle(List<T> remaining, Function<T, String> id, Function<T, List<String>> dependsOn) {
        Map<String, T> byId = new LinkedHashMap<>();
        for (T item : remaining) if (id.apply(item) != null) byId.put(id.apply(item), item);
        var path = new ArrayList<String>();
        String current = byId.keySet().iterator().next();
        while (!path.contains(current)) {
            path.add(current);
            current = dependsOn.apply(byId.get(current)).stream().filter(byId::containsKey).findFirst().orElseThrow();
        }
        var loop = new ArrayList<>(path.subList(path.indexOf(current), path.size()));
        loop.add(current);
        return String.join(" -> ", loop);
    }
}

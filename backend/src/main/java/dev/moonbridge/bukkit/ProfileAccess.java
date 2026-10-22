package dev.moonbridge.bukkit;

import java.util.logging.Logger;

/**
 * How the backend reaches a player's GameProfile properties on the running server. Bukkit has no API for them, so
 * each server family needs its own access. A compiled adapter is chosen once at startup; servers without one use the
 * cached reflective fallback. The two server families never share class names (CraftBukkit's package is versioned
 * only on Spigot, and only Spigot relocates authlib), so at most one compiled adapter can load.
 */
interface ProfileAccess {
    /** True when this access knows the player's class; other players go to the reflective fallback. */
    boolean handles(Object player);

    /** The player's profile property map, or null when this server does not expose one for the player. */
    Object properties(Object player);

    /** The properties stored under {@code name}; null when the map cannot be read. */
    Iterable<?> get(Object properties, String name);

    boolean remove(Object properties, String name, Object property);

    /** The property's value; anything other than a String is treated as invalid by the caller. */
    Object value(Object property);

    /** A short name for the startup log. */
    String description();

    /** The first compiled adapter whose classes are present (Uranium, then Spigot 1.7.10), else the fallback. */
    static ProfileAccess select() {
        ProfileAccess selected;
        try {
            selected = UraniumProfileAccess.create();
        } catch (LinkageError notUranium) {
            try {
                selected = SpigotProfileAccess.create();
            } catch (LinkageError notSpigot) {
                selected = ReflectiveProfileAccess.INSTANCE;
            }
        }
        Logger.getLogger(ProfileAccess.class.getName()).info("GameProfile access: " + selected.description());
        return selected;
    }
}

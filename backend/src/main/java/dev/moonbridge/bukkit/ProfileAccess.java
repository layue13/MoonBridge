package dev.moonbridge.bukkit;

import java.util.logging.Logger;

/**
 * How the backend reaches a player's GameProfile properties on the running server. Bukkit has no API for them, so
 * each server family needs its own access. A compiled adapter is chosen once at startup; servers without one use the
 * cached reflective fallback.
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

    /** The compiled Uranium adapter when its classes are present, otherwise the reflective fallback. */
    static ProfileAccess select() {
        ProfileAccess selected;
        try {
            selected = UraniumProfileAccess.create();
        } catch (LinkageError notUranium) {
            selected = ReflectiveProfileAccess.INSTANCE;
        }
        Logger.getLogger(ProfileAccess.class.getName()).info("GameProfile access: " + selected.description());
        return selected;
    }
}

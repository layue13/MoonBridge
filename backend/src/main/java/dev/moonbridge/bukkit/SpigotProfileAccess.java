package dev.moonbridge.bukkit;

import net.minecraft.util.com.mojang.authlib.GameProfile;
import net.minecraft.util.com.mojang.authlib.properties.Property;
import net.minecraft.util.com.mojang.authlib.properties.PropertyMap;
import org.bukkit.craftbukkit.v1_7_R4.entity.CraftPlayer;

/**
 * Direct calls into Spigot and CraftBukkit 1.7.10 (v1_7_R4), which relocate authlib and Guava under
 * {@code net.minecraft.util}. Compiled against the stubs in src/serverStubs, whose signatures were read with javap from
 * spigot-1.7.10-R0.1-SNAPSHOT (build 1649) and craftbukkit-1.7.10-R0.1; both builds declare them identically.
 */
final class SpigotProfileAccess implements ProfileAccess {
    private SpigotProfileAccess() { }

    /** Throws {@link LinkageError} when the server lacks these classes, so selection happens at startup. */
    static SpigotProfileAccess create() {
        // Class literals load the classes now rather than on the first join.
        Class<?>[] required = {CraftPlayer.class, GameProfile.class, PropertyMap.class, Property.class};
        return required.length > 0 ? new SpigotProfileAccess() : null;
    }

    @Override public boolean handles(Object player) { return player instanceof CraftPlayer; }

    @Override public Object properties(Object player) {
        if (!(player instanceof CraftPlayer)) return null;
        GameProfile profile = ((CraftPlayer) player).getProfile();
        return profile == null ? null : profile.getProperties();
    }

    @Override public Iterable<?> get(Object properties, String name) {
        return ((PropertyMap) properties).get(name);
    }

    @Override public boolean remove(Object properties, String name, Object property) {
        return ((PropertyMap) properties).remove(name, property);
    }

    @Override public Object value(Object property) {
        return ((Property) property).getValue();
    }

    @Override public String description() { return "compiled Spigot/CraftBukkit 1.7.10 adapter"; }
}

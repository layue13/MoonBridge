package dev.moonbridge.bukkit;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import org.bukkit.craftbukkit.entity.CraftPlayer;

/**
 * Direct calls into Uranium (KCauldron lineage: CraftBukkit classes without a version package, authlib unrelocated).
 * Compiled against the stubs in src/serverStubs, which match Uranium's CraftPlayer and authlib 1.5.16; no reflection.
 * PropertyMap's get and remove are inherited from Guava's ForwardingMultimap and resolve at run time, so this class
 * does not depend on which of Uranium's Guava copies backs it.
 */
final class UraniumProfileAccess implements ProfileAccess {
    private UraniumProfileAccess() { }

    /** Throws {@link LinkageError} when the server lacks these classes, so selection happens at startup. */
    static UraniumProfileAccess create() {
        // Class literals load the classes now rather than on the first join.
        Class<?>[] required = {CraftPlayer.class, GameProfile.class, PropertyMap.class, Property.class};
        return required.length > 0 ? new UraniumProfileAccess() : null;
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

    @Override public String description() { return "compiled Uranium adapter"; }
}

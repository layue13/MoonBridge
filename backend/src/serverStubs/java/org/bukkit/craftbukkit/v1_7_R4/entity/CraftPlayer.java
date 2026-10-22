package org.bukkit.craftbukkit.v1_7_R4.entity;

import net.minecraft.util.com.mojang.authlib.GameProfile;

/** Stub of Spigot/CraftBukkit 1.7.10's CraftPlayer: only the method the backend calls. */
public class CraftPlayer {
    private final GameProfile profile;

    public CraftPlayer(GameProfile profile) { this.profile = profile; }

    public GameProfile getProfile() { return profile; }
}

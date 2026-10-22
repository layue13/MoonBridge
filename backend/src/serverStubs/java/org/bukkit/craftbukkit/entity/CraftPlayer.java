package org.bukkit.craftbukkit.entity;

import com.mojang.authlib.GameProfile;

/** Stub of Uranium's CraftPlayer: only the method the backend calls. */
public class CraftPlayer {
    private final GameProfile profile;

    public CraftPlayer(GameProfile profile) { this.profile = profile; }

    public GameProfile getProfile() { return profile; }
}

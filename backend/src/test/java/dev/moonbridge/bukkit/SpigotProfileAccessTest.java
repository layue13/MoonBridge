package dev.moonbridge.bukkit;

import dev.moonbridge.messaging.session.ForwardedSessionProof;
import net.minecraft.util.com.mojang.authlib.GameProfile;
import net.minecraft.util.com.mojang.authlib.properties.Property;
import org.bukkit.craftbukkit.v1_7_R4.entity.CraftPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The compiled Spigot/CraftBukkit 1.7.10 adapter against stubs carrying the javap-verified signatures. */
class SpigotProfileAccessTest {
    @Test void compiledAdapterRemovesTheProofAndKeepsOtherProperties() {
        GameProfile profile = new GameProfile();
        Property proof = new Property(ForwardedSessionProof.PROPERTY_NAME, "proof-value");
        Property textures = new Property("textures", "texture-value");
        profile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME, proof);
        profile.getProperties().put("textures", textures);
        SpigotProfileAccess access = SpigotProfileAccess.create();
        CraftPlayer player = new CraftPlayer(profile);

        assertTrue(access.handles(player));
        OwnedBukkitSessionService.ProofProperty stripped = OwnedBukkitSessionService.stripForwardedProof(access, player);

        assertTrue(stripped.removed);
        assertEquals("proof-value", stripped.proof);
        assertTrue(profile.getProperties().get(ForwardedSessionProof.PROPERTY_NAME).isEmpty());
        assertSame(textures, profile.getProperties().get("textures").iterator().next());
    }

    @Test void eachAdapterOnlyHandlesItsOwnServersPlayers() {
        Object uraniumPlayer = new org.bukkit.craftbukkit.entity.CraftPlayer(new com.mojang.authlib.GameProfile());
        Object spigotPlayer = new CraftPlayer(new GameProfile());
        assertFalse(SpigotProfileAccess.create().handles(uraniumPlayer));
        assertFalse(UraniumProfileAccess.create().handles(spigotPlayer));
        assertTrue(SpigotProfileAccess.create().handles(spigotPlayer));
    }
}

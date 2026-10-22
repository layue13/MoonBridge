package dev.moonbridge.bukkit;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.moonbridge.messaging.session.ForwardedSessionProof;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The compiled adapter against the server stubs, which carry Uranium's and authlib 1.5.16's exact signatures. */
class UraniumProfileAccessTest {
    @Test void compiledAdapterIsSelectedWhenTheUraniumClassesArePresent() {
        assertTrue(ProfileAccess.select() instanceof UraniumProfileAccess);
    }

    @Test void compiledAdapterRemovesTheProofAndKeepsOtherProperties() {
        GameProfile profile = new GameProfile();
        Property proof = new Property(ForwardedSessionProof.PROPERTY_NAME, "proof-value");
        Property textures = new Property("textures", "texture-value");
        profile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME, proof);
        profile.getProperties().put("textures", textures);
        UraniumProfileAccess access = UraniumProfileAccess.create();
        CraftPlayer player = new CraftPlayer(profile);

        assertTrue(access.handles(player));
        OwnedBukkitSessionService.ProofProperty stripped = OwnedBukkitSessionService.stripForwardedProof(access, player);

        assertTrue(stripped.removed);
        assertEquals("proof-value", stripped.proof);
        assertTrue(profile.getProperties().get(ForwardedSessionProof.PROPERTY_NAME).isEmpty());
        assertSame(textures, profile.getProperties().get("textures").iterator().next());
    }

    @Test void duplicateProofsAreRemovedButYieldNoValue() {
        GameProfile profile = new GameProfile();
        profile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME, new Property(ForwardedSessionProof.PROPERTY_NAME, "a"));
        profile.getProperties().put(ForwardedSessionProof.PROPERTY_NAME, new Property(ForwardedSessionProof.PROPERTY_NAME, "b"));

        OwnedBukkitSessionService.ProofProperty stripped =
                OwnedBukkitSessionService.stripForwardedProof(UraniumProfileAccess.create(), new CraftPlayer(profile));

        assertTrue(stripped.removed);
        assertNull(stripped.proof);
        assertTrue(profile.getProperties().get(ForwardedSessionProof.PROPERTY_NAME).isEmpty());
    }

    @Test void playersThatAreNotCraftPlayersAreLeftToTheFallback() {
        UraniumProfileAccess access = UraniumProfileAccess.create();
        assertFalse(access.handles(new Object()));
        assertFalse(OwnedBukkitSessionService.stripForwardedProof(access, new Object()).foundProperty);
    }
}

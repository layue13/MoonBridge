package dev.strataproxy.network;

import org.junit.jupiter.api.Test;

import dev.strataproxy.protocol.ProtocolState;

import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftProtocolProfileTest {
    @Test
    void resolvesLegacy1710Profile() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);

        assertFalse(profile.compressionNegotiationSupported());
        assertEquals(OptionalInt.empty(), profile.clientboundLoginSetCompressionPacketId());
        assertEquals(Set.of(0x01), profile.serverboundCommandPacketIds());
        assertEquals(OptionalInt.of(0x17), profile.serverboundCustomPayloadPacketId());
        assertEquals(MinecraftProtocolProfile.CustomPayloadLengthFormat.UNSIGNED_SHORT,
                profile.serverboundCustomPayloadLengthFormat());
        assertEquals(ProtocolState.PLAY, profile.serverboundCustomPayloadInspectionState());
        assertEquals(OptionalInt.of(0x3F), profile.clientboundCustomPayloadPacketId());
        assertEquals(MinecraftProtocolProfile.CustomPayloadLengthFormat.VARSHORT,
                profile.clientboundCustomPayloadLengthFormat());
        assertEquals(OptionalInt.of(0x38), profile.clientboundPlayerListItemPacketId());
        assertEquals(MinecraftProtocolProfile.PlayerListItemLayout.LEGACY_NAME,
                profile.playerListItemLayout());
        assertEquals(OptionalInt.of(0x3B), profile.clientboundScoreboardObjectivePacketId());
        assertEquals(MinecraftProtocolProfile.ScoreboardObjectiveLayout.NAME_ACTION,
                profile.scoreboardObjectiveLayout());
        assertEquals(OptionalInt.of(0x3E), profile.clientboundTeamPacketId());
        assertEquals(MinecraftProtocolProfile.JoinGameDimensionLayout.BYTE,
                profile.joinGameDimensionLayout(false));
        assertEquals(MinecraftProtocolProfile.JoinGameDimensionLayout.INT,
                profile.joinGameDimensionLayout(true));
        assertEquals(MinecraftProtocolProfile.BackendSwitchStrategy.RESPAWN_ONLY,
                profile.backendSwitchStrategy(false));
        assertEquals(MinecraftProtocolProfile.BackendSwitchStrategy.JOIN_GAME_THEN_RESPAWN,
                profile.backendSwitchStrategy(true));
        assertTrue(profile.backendReplacementSupported());
        assertTrue(profile.legacyForgeHandshakeSupported());
    }

    @Test
    void resolvesLegacy18ProfileExplicitly() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);

        assertTrue(profile.compressionNegotiationSupported());
        assertEquals(OptionalInt.of(0x03), profile.clientboundLoginSetCompressionPacketId());
        assertEquals(Set.of(0x01), profile.serverboundCommandPacketIds());
        assertEquals(OptionalInt.of(0x02), profile.clientboundChatPacketId());
        assertTrue(profile.clientboundChatHasOverlayFlag());
        assertEquals(OptionalInt.of(0x17), profile.serverboundCustomPayloadPacketId());
        assertEquals(MinecraftProtocolProfile.CustomPayloadLengthFormat.REMAINING_BYTES,
                profile.serverboundCustomPayloadLengthFormat());
        assertEquals(ProtocolState.PLAY, profile.serverboundCustomPayloadInspectionState());
        assertEquals(OptionalInt.of(0x3F), profile.clientboundCustomPayloadPacketId());
        assertEquals(MinecraftProtocolProfile.CustomPayloadLengthFormat.REMAINING_BYTES,
                profile.clientboundCustomPayloadLengthFormat());
        assertEquals(OptionalInt.of(0x01), profile.clientboundPlayLoginPacketId());
        assertEquals(OptionalInt.of(0x07), profile.clientboundPlayRespawnPacketId());
        assertEquals(OptionalInt.of(0x38), profile.clientboundPlayerListItemPacketId());
        assertEquals(MinecraftProtocolProfile.PlayerListItemLayout.UUID_ACTION,
                profile.playerListItemLayout());
        assertEquals(OptionalInt.of(0x3B), profile.clientboundScoreboardObjectivePacketId());
        assertEquals(MinecraftProtocolProfile.ScoreboardObjectiveLayout.NAME_ACTION,
                profile.scoreboardObjectiveLayout());
        assertEquals(OptionalInt.of(0x3E), profile.clientboundTeamPacketId());
        assertEquals(MinecraftProtocolProfile.JoinGameDimensionLayout.BYTE,
                profile.joinGameDimensionLayout(false));
        assertEquals(MinecraftProtocolProfile.JoinGameDimensionLayout.BYTE,
                profile.joinGameDimensionLayout(true));
        assertEquals(MinecraftProtocolProfile.BackendSwitchStrategy.RESPAWN_ONLY,
                profile.backendSwitchStrategy(false));
        assertEquals(MinecraftProtocolProfile.BackendSwitchStrategy.JOIN_GAME_THEN_RESPAWN,
                profile.backendSwitchStrategy(true));
        assertTrue(profile.backendReplacementSupported());
        assertTrue(profile.legacyForgeHandshakeSupported());
    }

    @Test
    void resolvesKnownModernProfileAsReplacementSupported() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1);

        assertTrue(profile.compressionNegotiationSupported());
        assertEquals(OptionalInt.of(0x03), profile.clientboundLoginSetCompressionPacketId());
        assertEquals(ProtocolState.CONFIGURATION, profile.serverboundCustomPayloadInspectionState());
        assertEquals(MinecraftProtocolProfile.BackendSwitchStrategy.NONE,
                profile.backendSwitchStrategy(false));
        assertEquals(MinecraftProtocolProfile.JoinGameDimensionLayout.NONE,
                profile.joinGameDimensionLayout(false));
        assertTrue(profile.backendReplacementSupported());
        assertFalse(profile.legacyForgeHandshakeSupported());
    }

    @Test
    void unknownModernProfileRemainsConservative() {
        var profile = MinecraftProtocolProfile.forVersion(9999);

        assertTrue(profile.compressionNegotiationSupported());
        assertEquals(OptionalInt.of(0x03), profile.clientboundLoginSetCompressionPacketId());
        assertTrue(profile.serverboundCommandPacketIds().isEmpty());
        assertEquals(OptionalInt.empty(), profile.serverboundCustomPayloadPacketId());
        assertEquals(ProtocolState.PLAY, profile.serverboundCustomPayloadInspectionState());
        assertEquals(OptionalInt.empty(), profile.clientboundCustomPayloadPacketId());
        assertEquals(OptionalInt.empty(), profile.clientboundPlayerListItemPacketId());
        assertEquals(MinecraftProtocolProfile.PlayerListItemLayout.NONE,
                profile.playerListItemLayout());
        assertEquals(OptionalInt.empty(), profile.clientboundScoreboardObjectivePacketId());
        assertEquals(MinecraftProtocolProfile.ScoreboardObjectiveLayout.NONE,
                profile.scoreboardObjectiveLayout());
        assertEquals(OptionalInt.empty(), profile.clientboundTeamPacketId());
        assertEquals(MinecraftProtocolProfile.BackendSwitchStrategy.NONE,
                profile.backendSwitchStrategy(true));
        assertFalse(profile.backendReplacementSupported());
        assertFalse(profile.legacyForgeHandshakeSupported());
    }
}

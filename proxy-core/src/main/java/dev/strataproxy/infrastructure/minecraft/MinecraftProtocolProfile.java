package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.domain.protocol.ProtocolState;

import java.util.OptionalInt;
import java.util.Set;

final class MinecraftProtocolProfile {
    static final int PROTOCOL_1_7_10 = 5;
    static final int PROTOCOL_1_8 = 47;
    static final int PROTOCOL_1_20_1 = 763;

    private static final MinecraftProtocolProfile LEGACY_1_7_10 = new MinecraftProtocolProfile(
            PROTOCOL_1_7_10,
            // HexaCord registers a 1.7.x play-state SetCompression packet, but only enables
            // login compression for >= 1.8 clients. Protocol 5 must not negotiate vanilla
            // login compression.
            false,
            0x00,
            0x01,
            0x02,
            OptionalInt.empty(),
            Set.of(0x01),
            OptionalInt.of(0x02),
            false,
            OptionalInt.of(0x17),
            Set.of(0x17),
            CustomPayloadLengthFormat.UNSIGNED_SHORT,
            ProtocolState.PLAY,
            OptionalInt.of(0x3F),
            CustomPayloadLengthFormat.VARSHORT,
            OptionalInt.of(0x01),
            OptionalInt.of(0x07),
            JoinGameDimensionLayout.BYTE,
            JoinGameDimensionLayout.INT,
            OptionalInt.of(0x38),
            PlayerListItemLayout.LEGACY_NAME,
            OptionalInt.of(0x3B),
            ScoreboardObjectiveLayout.NAME_ACTION,
            OptionalInt.of(0x3E),
            BackendSwitchStrategy.RESPAWN_ONLY,
            BackendSwitchStrategy.JOIN_GAME_THEN_RESPAWN,
            true,
            true);

    private static final MinecraftProtocolProfile LEGACY_1_8 = new MinecraftProtocolProfile(
            PROTOCOL_1_8,
            true,
            0x00,
            0x01,
            0x02,
            OptionalInt.of(0x03),
            Set.of(0x01),
            OptionalInt.of(0x02),
            true,
            OptionalInt.of(0x17),
            Set.of(0x17),
            CustomPayloadLengthFormat.REMAINING_BYTES,
            ProtocolState.PLAY,
            OptionalInt.of(0x3F),
            CustomPayloadLengthFormat.REMAINING_BYTES,
            OptionalInt.of(0x01),
            OptionalInt.of(0x07),
            JoinGameDimensionLayout.BYTE,
            JoinGameDimensionLayout.BYTE,
            OptionalInt.of(0x38),
            PlayerListItemLayout.UUID_ACTION,
            OptionalInt.of(0x3B),
            ScoreboardObjectiveLayout.NAME_ACTION,
            OptionalInt.of(0x3E),
            BackendSwitchStrategy.RESPAWN_ONLY,
            BackendSwitchStrategy.JOIN_GAME_THEN_RESPAWN,
            true,
            true);

    private static final MinecraftProtocolProfile MODERN_1_20_1 = new MinecraftProtocolProfile(
            PROTOCOL_1_20_1,
            true,
            0x00,
            0x01,
            0x02,
            OptionalInt.of(0x03),
            Set.of(0x03, 0x04, 0x05),
            OptionalInt.of(0x64),
            true,
            OptionalInt.of(0x01),
            Set.of(0x18),
            CustomPayloadLengthFormat.REMAINING_BYTES,
            ProtocolState.CONFIGURATION,
            OptionalInt.empty(),
            CustomPayloadLengthFormat.REMAINING_BYTES,
            OptionalInt.empty(),
            OptionalInt.empty(),
            JoinGameDimensionLayout.NONE,
            JoinGameDimensionLayout.NONE,
            OptionalInt.empty(),
            PlayerListItemLayout.NONE,
            OptionalInt.empty(),
            ScoreboardObjectiveLayout.NONE,
            OptionalInt.empty(),
            BackendSwitchStrategy.NONE,
            BackendSwitchStrategy.NONE,
            false,
            true);

    private final int protocolVersion;
    private final boolean compressionNegotiationSupported;
    private final int clientboundLoginDisconnectPacketId;
    private final int clientboundLoginEncryptionRequestPacketId;
    private final int clientboundLoginSuccessPacketId;
    private final OptionalInt clientboundLoginSetCompressionPacketId;
    private final Set<Integer> serverboundCommandPacketIds;
    private final OptionalInt clientboundChatPacketId;
    private final boolean clientboundChatHasOverlayFlag;
    private final OptionalInt serverboundCustomPayloadPacketId;
    private final Set<Integer> serverboundBungeeCustomPayloadPacketIds;
    private final CustomPayloadLengthFormat serverboundCustomPayloadLengthFormat;
    private final ProtocolState serverboundCustomPayloadInspectionState;
    private final OptionalInt clientboundCustomPayloadPacketId;
    private final CustomPayloadLengthFormat clientboundCustomPayloadLengthFormat;
    private final OptionalInt clientboundPlayLoginPacketId;
    private final OptionalInt clientboundPlayRespawnPacketId;
    private final JoinGameDimensionLayout joinGameDimensionLayout;
    private final JoinGameDimensionLayout legacyForgeJoinGameDimensionLayout;
    private final OptionalInt clientboundPlayerListItemPacketId;
    private final PlayerListItemLayout playerListItemLayout;
    private final OptionalInt clientboundScoreboardObjectivePacketId;
    private final ScoreboardObjectiveLayout scoreboardObjectiveLayout;
    private final OptionalInt clientboundTeamPacketId;
    private final BackendSwitchStrategy backendSwitchStrategy;
    private final BackendSwitchStrategy legacyForgeBackendSwitchStrategy;
    private final boolean legacyForgeHandshakeSupported;
    private final boolean backendReplacementSupported;

    private MinecraftProtocolProfile(
            int protocolVersion,
            boolean compressionNegotiationSupported,
            int clientboundLoginDisconnectPacketId,
            int clientboundLoginEncryptionRequestPacketId,
            int clientboundLoginSuccessPacketId,
            OptionalInt clientboundLoginSetCompressionPacketId,
            Set<Integer> serverboundCommandPacketIds,
            OptionalInt clientboundChatPacketId,
            boolean clientboundChatHasOverlayFlag,
            OptionalInt serverboundCustomPayloadPacketId,
            Set<Integer> serverboundBungeeCustomPayloadPacketIds,
            CustomPayloadLengthFormat serverboundCustomPayloadLengthFormat,
            ProtocolState serverboundCustomPayloadInspectionState,
            OptionalInt clientboundCustomPayloadPacketId,
            CustomPayloadLengthFormat clientboundCustomPayloadLengthFormat,
            OptionalInt clientboundPlayLoginPacketId,
            OptionalInt clientboundPlayRespawnPacketId,
            JoinGameDimensionLayout joinGameDimensionLayout,
            JoinGameDimensionLayout legacyForgeJoinGameDimensionLayout,
            OptionalInt clientboundPlayerListItemPacketId,
            PlayerListItemLayout playerListItemLayout,
            OptionalInt clientboundScoreboardObjectivePacketId,
            ScoreboardObjectiveLayout scoreboardObjectiveLayout,
            OptionalInt clientboundTeamPacketId,
            BackendSwitchStrategy backendSwitchStrategy,
            BackendSwitchStrategy legacyForgeBackendSwitchStrategy,
            boolean legacyForgeHandshakeSupported,
            boolean backendReplacementSupported) {
        this.protocolVersion = protocolVersion;
        this.compressionNegotiationSupported = compressionNegotiationSupported;
        this.clientboundLoginDisconnectPacketId = clientboundLoginDisconnectPacketId;
        this.clientboundLoginEncryptionRequestPacketId = clientboundLoginEncryptionRequestPacketId;
        this.clientboundLoginSuccessPacketId = clientboundLoginSuccessPacketId;
        this.clientboundLoginSetCompressionPacketId = clientboundLoginSetCompressionPacketId;
        this.serverboundCommandPacketIds = Set.copyOf(serverboundCommandPacketIds);
        this.clientboundChatPacketId = clientboundChatPacketId;
        this.clientboundChatHasOverlayFlag = clientboundChatHasOverlayFlag;
        this.serverboundCustomPayloadPacketId = serverboundCustomPayloadPacketId;
        this.serverboundBungeeCustomPayloadPacketIds = Set.copyOf(serverboundBungeeCustomPayloadPacketIds);
        this.serverboundCustomPayloadLengthFormat = serverboundCustomPayloadLengthFormat;
        this.serverboundCustomPayloadInspectionState = serverboundCustomPayloadInspectionState == null
                ? ProtocolState.PLAY
                : serverboundCustomPayloadInspectionState;
        this.clientboundCustomPayloadPacketId = clientboundCustomPayloadPacketId;
        this.clientboundCustomPayloadLengthFormat = clientboundCustomPayloadLengthFormat;
        this.clientboundPlayLoginPacketId = clientboundPlayLoginPacketId;
        this.clientboundPlayRespawnPacketId = clientboundPlayRespawnPacketId;
        this.joinGameDimensionLayout = joinGameDimensionLayout == null
                ? JoinGameDimensionLayout.NONE
                : joinGameDimensionLayout;
        this.legacyForgeJoinGameDimensionLayout = legacyForgeJoinGameDimensionLayout == null
                ? this.joinGameDimensionLayout
                : legacyForgeJoinGameDimensionLayout;
        this.clientboundPlayerListItemPacketId = clientboundPlayerListItemPacketId;
        this.playerListItemLayout = playerListItemLayout == null ? PlayerListItemLayout.NONE : playerListItemLayout;
        this.clientboundScoreboardObjectivePacketId = clientboundScoreboardObjectivePacketId;
        this.scoreboardObjectiveLayout = scoreboardObjectiveLayout == null
                ? ScoreboardObjectiveLayout.NONE
                : scoreboardObjectiveLayout;
        this.clientboundTeamPacketId = clientboundTeamPacketId;
        this.backendSwitchStrategy = backendSwitchStrategy == null ? BackendSwitchStrategy.NONE : backendSwitchStrategy;
        this.legacyForgeBackendSwitchStrategy = legacyForgeBackendSwitchStrategy == null
                ? this.backendSwitchStrategy
                : legacyForgeBackendSwitchStrategy;
        this.legacyForgeHandshakeSupported = legacyForgeHandshakeSupported;
        this.backendReplacementSupported = backendReplacementSupported;
    }

    static MinecraftProtocolProfile forVersion(int protocolVersion) {
        if (protocolVersion == PROTOCOL_1_7_10) {
            return LEGACY_1_7_10;
        }
        if (protocolVersion == PROTOCOL_1_8) {
            return LEGACY_1_8;
        }
        if (protocolVersion == PROTOCOL_1_20_1) {
            return MODERN_1_20_1;
        }
        return new MinecraftProtocolProfile(
                protocolVersion,
                protocolVersion >= PROTOCOL_1_8,
                0x00,
                0x01,
                0x02,
                protocolVersion >= PROTOCOL_1_8 ? OptionalInt.of(0x03) : OptionalInt.empty(),
                Set.of(),
                OptionalInt.empty(),
                false,
                OptionalInt.empty(),
                Set.of(),
                CustomPayloadLengthFormat.REMAINING_BYTES,
                ProtocolState.PLAY,
                OptionalInt.empty(),
                CustomPayloadLengthFormat.REMAINING_BYTES,
                OptionalInt.empty(),
                OptionalInt.empty(),
                JoinGameDimensionLayout.NONE,
                JoinGameDimensionLayout.NONE,
                OptionalInt.empty(),
                PlayerListItemLayout.NONE,
                OptionalInt.empty(),
                ScoreboardObjectiveLayout.NONE,
                OptionalInt.empty(),
                BackendSwitchStrategy.NONE,
                BackendSwitchStrategy.NONE,
                false,
                false);
    }

    int protocolVersion() {
        return protocolVersion;
    }

    boolean compressionNegotiationSupported() {
        return compressionNegotiationSupported;
    }

    int clientboundLoginDisconnectPacketId() {
        return clientboundLoginDisconnectPacketId;
    }

    int clientboundLoginEncryptionRequestPacketId() {
        return clientboundLoginEncryptionRequestPacketId;
    }

    int clientboundLoginSuccessPacketId() {
        return clientboundLoginSuccessPacketId;
    }

    OptionalInt clientboundLoginSetCompressionPacketId() {
        return clientboundLoginSetCompressionPacketId;
    }

    Set<Integer> serverboundCommandPacketIds() {
        return serverboundCommandPacketIds;
    }

    OptionalInt clientboundChatPacketId() {
        return clientboundChatPacketId;
    }

    boolean clientboundChatHasOverlayFlag() {
        return clientboundChatHasOverlayFlag;
    }

    OptionalInt serverboundCustomPayloadPacketId() {
        return serverboundCustomPayloadPacketId;
    }

    Set<Integer> serverboundBungeeCustomPayloadPacketIds() {
        return serverboundBungeeCustomPayloadPacketIds;
    }

    CustomPayloadLengthFormat serverboundCustomPayloadLengthFormat() {
        return serverboundCustomPayloadLengthFormat;
    }

    ProtocolState serverboundCustomPayloadInspectionState() {
        return serverboundCustomPayloadInspectionState;
    }

    OptionalInt clientboundCustomPayloadPacketId() {
        return clientboundCustomPayloadPacketId;
    }

    CustomPayloadLengthFormat clientboundCustomPayloadLengthFormat() {
        return clientboundCustomPayloadLengthFormat;
    }

    OptionalInt clientboundPlayLoginPacketId() {
        return clientboundPlayLoginPacketId;
    }

    OptionalInt clientboundPlayRespawnPacketId() {
        return clientboundPlayRespawnPacketId;
    }

    JoinGameDimensionLayout joinGameDimensionLayout(boolean legacyForgeClient) {
        return legacyForgeClient && legacyForgeHandshakeSupported
                ? legacyForgeJoinGameDimensionLayout
                : joinGameDimensionLayout;
    }

    OptionalInt clientboundPlayerListItemPacketId() {
        return clientboundPlayerListItemPacketId;
    }

    PlayerListItemLayout playerListItemLayout() {
        return playerListItemLayout;
    }

    OptionalInt clientboundScoreboardObjectivePacketId() {
        return clientboundScoreboardObjectivePacketId;
    }

    ScoreboardObjectiveLayout scoreboardObjectiveLayout() {
        return scoreboardObjectiveLayout;
    }

    OptionalInt clientboundTeamPacketId() {
        return clientboundTeamPacketId;
    }

    boolean backendSwitchUsesRespawn() {
        return backendSwitchStrategy != BackendSwitchStrategy.NONE;
    }

    boolean backendReplacementSupported() {
        return backendReplacementSupported;
    }

    BackendSwitchStrategy backendSwitchStrategy(boolean legacyForgeClient) {
        return legacyForgeClient && legacyForgeHandshakeSupported
                ? legacyForgeBackendSwitchStrategy
                : backendSwitchStrategy;
    }

    boolean legacyForgeHandshakeSupported() {
        return legacyForgeHandshakeSupported;
    }

    enum CustomPayloadLengthFormat {
        REMAINING_BYTES,
        UNSIGNED_SHORT,
        VARSHORT
    }

    enum PlayerListItemLayout {
        NONE,
        LEGACY_NAME,
        UUID_ACTION
    }

    enum JoinGameDimensionLayout {
        NONE,
        BYTE,
        INT
    }

    enum ScoreboardObjectiveLayout {
        NONE,
        NAME_VALUE_ACTION,
        NAME_ACTION
    }

    enum BackendSwitchStrategy {
        NONE,
        RESPAWN_ONLY,
        JOIN_GAME_THEN_RESPAWN
    }
}

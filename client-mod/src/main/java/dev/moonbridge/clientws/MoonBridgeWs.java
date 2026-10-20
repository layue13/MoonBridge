package dev.moonbridge.clientws;

import cpw.mods.fml.common.Mod;

/**
 * Marker mod so the coremod shows up in the mod list. All behaviour lives in the early mixins
 * ({@code dev.moonbridge.clientws.mixins.early}); nothing is registered here and the mod is not required on servers.
 */
@Mod(
    modid = MoonBridgeWs.MODID,
    name = "MoonBridge WebSocket",
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public class MoonBridgeWs {

    public static final String MODID = "moonbridgews";
}

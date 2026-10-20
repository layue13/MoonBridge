package dev.moonbridge.clientws.mixins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;

import cpw.mods.fml.relauncher.FMLLaunchHandler;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

/**
 * Coremod entry point. It only registers the early (vanilla-targeting) mixins with the GTNH mixin loader, which
 * UniMixins provides at runtime; there are no ASM transformers.
 */
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.Name("MoonBridgeWsCore")
public class EarlyMixinsLoader implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.moonbridgews.early.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedCoreMods) {
        List<String> mixins = new ArrayList<String>();
        if (FMLLaunchHandler.side()
            .isClient()) {
            mixins.add("MixinServerAddress");
            mixins.add("ServerAddressInvoker");
            mixins.add("MixinNetworkManager");
            mixins.add("MixinNetworkManagerChannelInitializer");
            mixins.add("MixinGuiConnecting");
            mixins.add("MixinOldServerPinger");
        }
        return mixins;
    }

    @Override
    public String[] getASMTransformerClass() {
        return null;
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {}

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}

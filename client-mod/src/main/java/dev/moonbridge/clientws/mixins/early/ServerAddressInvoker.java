package dev.moonbridge.clientws.mixins.early;

import net.minecraft.client.multiplayer.ServerAddress;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Access to the protected {@code ServerAddress(String, int)} constructor. */
@Mixin(ServerAddress.class)
public interface ServerAddressInvoker {

    @Invoker("<init>")
    static ServerAddress moonbridgews$create(String host, int port) {
        throw new AssertionError("mixin not applied");
    }
}

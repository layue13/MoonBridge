package dev.moonbridge.smoke;

import cc.uraniummc.api.playerdata.PlayerDataAuthority;
import cc.uraniummc.api.playerdata.PlayerDataAuthorityProvider;
import cc.uraniummc.api.playerdata.PlayerDataLoadContext;
import cc.uraniummc.api.playerdata.Position;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Real Uranium managed-login acceptance probe; test-only, no persistence service. */
public final class PlayerDataAuthorityAcceptancePlugin extends JavaPlugin implements Listener {
    private static final String PROBE_NAME = "NettyProbe";
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger restores = new AtomicInteger();
    private final AtomicInteger loginChecks = new AtomicInteger();
    private final AtomicInteger postJoinChecks = new AtomicInteger();
    private final AtomicInteger suppressedLoads = new AtomicInteger();
    private final AtomicInteger quitSaves = new AtomicInteger();
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Set<UUID> quitting = ConcurrentHashMap.newKeySet();
    private volatile PlayerDataAuthority.Registration registration;
    private String node;
    private volatile boolean failed;

    @Override public void onEnable() {
        node = getConfig().getString("backendName", "").trim().toLowerCase();
        if (!node.equals("old") && !node.equals("new")) {
            throw new IllegalStateException("backendName must be old or new");
        }
        try {
            registration = PlayerDataAuthority.register(new ProbeProvider());
        } catch (RuntimeException conflict) {
            throw new IllegalStateException("Could not register the exclusive player-data probe", conflict);
        }
        // Force SCM to construct this login's PlayerLoginEvent with a native denial.
        Bukkit.getBanList(BanList.Type.NAME).addBan(PROBE_NAME,
                "Player-data authority acceptance probe", (Date) null, getName());
        if (!Bukkit.getBanList(BanList.Type.NAME).isBanned(PROBE_NAME)) {
            throw new IllegalStateException("Could not install the isolated name ban");
        }
        getServer().getPluginManager().registerEvents(this, this);
        MinecraftForge.EVENT_BUS.register(this);
        getLogger().info("PDA_PROBE_READY node=" + node + " nativeNameBan=true");
    }

    private final class ProbeProvider implements PlayerDataAuthorityProvider {
        @Override public PlayerDataLoadContext begin(Player player) throws Exception {
            if (!PROBE_NAME.equals(player.getName())) return null;
            if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("begin was not on the server thread");
            final int ordinal = logins.incrementAndGet();
            final int amount = (node.equals("old") ? 10 : 20) + ordinal;
            final int staleAmount = 40 + amount;
            if (node.equals("old") && ordinal == 2) {
                int savedAmount = readLocalDiamondCount(player.getUniqueId());
                if (savedAmount != 51) {
                    fail("returning A did not have first-exit local inventory marker 51; found " + savedAmount);
                    throw new IOException("Expected an existing stale A player file with 51 diamonds");
                }
                getLogger().info("PDA_PROBE_OLD_FILE_PASS node=old previousAmount=" + savedAmount);
            }
            State state = new State(player, player.getUniqueId(), ordinal, amount, staleAmount);
            states.put(player.getUniqueId(), state);
            Position position = node.equals("old") && ordinal == 2
                    ? new Position(9999, 0.5D, 70.0D, 0.5D, 0.0F, 0.0F)
                    : new Position(0, 0.5D, 70.0D, 0.5D, 0.0F, 0.0F);
            return new PlayerDataLoadContext() {
                @Override public Position position() { return position; }

                @Override public void restore(Player target) {
                    if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("restore was not on the server thread");
                    if (!state.uuid.equals(target.getUniqueId())) throw new IllegalStateException("wrong player context");
                    if (node.equals("old") && ordinal == 2) {
                        if (!target.getWorld().getName().equals(Bukkit.getWorlds().get(0).getName())) {
                            throw new IllegalStateException("invalid dimension did not fall back to overworld");
                        }
                        getLogger().info("PDA_PROBE_FALLBACK_PASS node=old login=2 world=" + target.getWorld().getName());
                    }
                    target.getInventory().clear();
                    target.getInventory().setItem(0, new ItemStack(Material.DIAMOND, amount));
                    target.loadData();
                    requireDiamondAmount(target, amount, "CraftPlayer.loadData inside restore");
                    state.restored = true;
                    restores.incrementAndGet();
                    getLogger().info("PDA_PROBE_RESTORE_PASS node=" + node + " login=" + ordinal
                            + " amount=" + amount + " loadData=retained");
                }

                @Override public void abort(Player target, Throwable cause) {
                    states.remove(state.uuid, state);
                    quitting.remove(state.uuid);
                    getLogger().warning("PDA_PROBE_ABORT node=" + node + " login=" + ordinal
                            + " reason=" + (cause == null ? "login-denied" : cause.getClass().getSimpleName()));
                }
            };
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void login(PlayerLoginEvent event) {
        if (!PROBE_NAME.equals(event.getPlayer().getName())) return;
        Player player = event.getPlayer();
        State state = states.get(player.getUniqueId());
        try {
            if (state == null || !state.restored) throw new AssertionError("login event ran before restore");
            requireDiamondAmount(player, state.amount, "PlayerLoginEvent");
            loginChecks.incrementAndGet();
            if (event.getResult() == PlayerLoginEvent.Result.ALLOWED) {
                throw new AssertionError("native name ban did not pre-deny PlayerLoginEvent");
            }
            PlayerLoginEvent.Result initial = event.getResult();
            event.setResult(PlayerLoginEvent.Result.ALLOWED);
            event.setKickMessage("");
            getLogger().info("PDA_PROBE_NATIVE_DENIAL_OVERRIDE_PASS node=" + node + " login=" + state.ordinal
                    + " initial=" + initial + " restoredBeforeEvent=true");
        } catch (Throwable failure) {
            fail("PlayerLoginEvent acceptance failed: " + failure);
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, "Player-data authority probe failed");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        State state = states.get(player.getUniqueId());
        if (state == null) return;
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                requireDiamondAmount(player, state.amount, "post-Join next tick");
                postJoinChecks.incrementAndGet();
                if (state.ordinal == 1 && node.equals("old")) {
                    // Persist a deliberately different local value before the transfer away from A.
                    player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, state.staleAmount));
                    player.saveData();
                    getLogger().info("PDA_PROBE_STALE_LOCAL_SEED_PASS node=old amount=" + state.staleAmount);
                } else {
                    player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, state.staleAmount));
                    player.saveData();
                }
                getLogger().info("PDA_PROBE_POST_JOIN_PASS node=" + node + " login=" + state.ordinal
                        + " authoritativeAmount=" + state.amount + " savedLocalMarker=" + state.staleAmount);
            } catch (Throwable failure) {
                fail("post-Join check failed: " + failure);
                player.kickPlayer("Player-data authority probe failed");
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        State state = states.get(event.getPlayer().getUniqueId());
        if (state == null) return;
        quitting.add(state.uuid);
        try {
            requireDiamondAmount(event.getPlayer(), state.staleAmount, "Bukkit Quit before Forge save");
        } catch (AssertionError failure) {
            fail(failure.getMessage());
        }
    }

    @SubscribeEvent
    public void loadFromFile(PlayerEvent.LoadFromFile event) {
        State state = states.get(event.entityPlayer.func_110124_au());
        if (state != null) {
            suppressedLoads.incrementAndGet();
            fail("Forge LoadFromFile fired for managed player " + state.uuid);
        }
    }

    @SubscribeEvent
    public void saveToFile(PlayerEvent.SaveToFile event) {
        UUID uuid = event.entityPlayer.func_110124_au();
        State state = states.get(uuid);
        if (state == null || !quitting.remove(uuid)) return;
        try {
            requireDiamondAmount(state.player, state.staleAmount, "Forge SaveToFile");
            quitSaves.incrementAndGet();
            getLogger().info("PDA_PROBE_QUIT_SAVE_PASS node=" + node + " login=" + state.ordinal
                    + " savedLocalMarker=" + state.staleAmount);
        } catch (AssertionError failure) {
            fail(failure.getMessage());
        }
    }

    private int readLocalDiamondCount(UUID uuid) throws IOException {
        File file = new File(new File(Bukkit.getWorlds().get(0).getWorldFolder(), "playerdata"), uuid + ".dat");
        if (!file.isFile()) return -1;
        NBTTagCompound data;
        try (FileInputStream input = new FileInputStream(file)) {
            data = CompressedStreamTools.func_74796_a(input);
        }
        NBTTagList inventory = data.func_150295_c("Inventory", 10);
        for (int i = 0; i < inventory.func_74745_c(); i++) {
            NBTTagCompound stack = inventory.func_150305_b(i);
            if (stack.func_74765_d("id") == (short) Item.func_150891_b(Items.field_151045_i)) {
                return stack.func_74771_c("Count") & 255;
            }
        }
        return 0;
    }

    private void requireDiamondAmount(Player player, int expected, String checkpoint) {
        ItemStack stack = player.getInventory().getItem(0);
        if (stack == null || stack.getType() != Material.DIAMOND || stack.getAmount() != expected) {
            throw new AssertionError(checkpoint + " expected " + expected + " diamonds, found "
                    + (stack == null ? "empty" : stack.getType() + "x" + stack.getAmount()));
        }
    }

    private void fail(String reason) {
        failed = true;
        getLogger().severe("PDA_PROBE_FAIL node=" + node + " reason=" + reason);
    }

    @Override public void onDisable() {
        PlayerDataAuthority.Registration handle = registration;
        if (handle != null) handle.close();
        MinecraftForge.EVENT_BUS.unregister(this);
        getLogger().info("PDA_PROBE_SUMMARY node=" + node + " logins=" + logins.get()
                + " restores=" + restores.get() + " loginChecks=" + loginChecks.get()
                + " postJoinChecks=" + postJoinChecks.get() + " LoadFromFile=" + suppressedLoads.get()
                + " quitSaveEvents=" + quitSaves.get() + " failed=" + failed);
    }

    private static final class State {
        private final Player player;
        private final UUID uuid;
        private final int ordinal;
        private final int amount;
        private final int staleAmount;
        private volatile boolean restored;

        private State(Player player, UUID uuid, int ordinal, int amount, int staleAmount) {
            this.player = player;
            this.uuid = uuid;
            this.ordinal = ordinal;
            this.amount = amount;
            this.staleAmount = staleAmount;
        }
    }
}


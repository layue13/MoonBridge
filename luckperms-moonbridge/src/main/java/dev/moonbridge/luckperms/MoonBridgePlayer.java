package dev.moonbridge.luckperms;

import dev.moonbridge.api.PlayerView;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Stable LuckPerms context subject for one MoonBridge connection. */
final class MoonBridgePlayer {
    private final AtomicReference<PlayerView> view;

    MoonBridgePlayer(PlayerView initial) {
        this.view = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    PlayerView view() { return view.get(); }
    void update(PlayerView next) { view.set(Objects.requireNonNull(next, "next")); }
}

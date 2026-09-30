# Uranium player-data authority boundary

Scope: an optional public-API acceptance probe on the new Uranium authority implementation. This does not load PlayerDataSQL or a modpack, and its source-release barrier does not confirm SQL persistence.

Harness: `local-uranium-transfer.ps1 -InstalledPlugin -ReleaseSource -ReturnToOld -DebugSession -AuthenticateEarly -ManagedPlayerData -UraniumApiJar <matching development jar> -BukkitApiJar <Spigot API>`.

Local run: `build/local-uranium-transfer-7237a6cadf8e45378742e866d5039d4f`. Server artifact SHA-256: `EBCC88FE572D4CC448C86D1619C1B01762BE1D69282016EA90EFB494737F703E`. Its source was the dirty Uranium `evo@325e7de5ee` authority implementation later committed as `13855d1`; this was not a published artifact.

Result: exit 0; real protocol A→B→A and both source-release barriers passed. A had two restored/logged-in admissions, B one. All three login events initially denied a native name ban and observed restored inventory before the probe overrode that denial. All three next-tick checks retained the restored inventory.

A's first exit persisted a deliberately stale local marker of 51 diamonds. Returning A read that existing file, restored 12, called `Player.loadData()`, and still observed 12 before login and after Join. The supplied invalid dimension 9999 fell back to the overworld. Managed Forge `LoadFromFile` events: 0. Normal quit `SaveToFile` events: A 2, B 1. Probe failures and event/linkage errors: 0.

An earlier run exposed `NoSuchMethodError` in the probe's direct NMS `getBukkitEntity` return descriptor (development Craft class package differs from runtime). The probe now retains its exact Bukkit player for validation, and the harness rejects event/linkage errors in addition to explicit assertion markers. Compilation by itself did not detect that runtime failure.

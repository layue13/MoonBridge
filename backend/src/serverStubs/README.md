Compile-only copies of the server classes the compiled profile adapters call; the bodies only serve tests.

- `UraniumProfileAccess`: Uranium (`TDLM/Uranium`, `org.bukkit.craftbukkit.entity.CraftPlayer#getProfile`) and
  authlib 1.5.16 (`com.mojang.authlib`).
- `SpigotProfileAccess`: Spigot and CraftBukkit 1.7.10 (`org.bukkit.craftbukkit.v1_7_R4.entity.CraftPlayer`, authlib
  relocated to `net.minecraft.util.com.mojang.authlib`), read with javap from spigot-1.7.10-R0.1-SNAPSHOT build 1649
  and craftbukkit-1.7.10-R0.1. This source set is never packaged: the running server provides the real classes, and
`verifyBackendJar` fails if any of them reaches the backend jar.

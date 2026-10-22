Compile-only copies of the server classes `UraniumProfileAccess` calls. Their names and signatures match Uranium
(`TDLM/Uranium`, `org.bukkit.craftbukkit.entity.CraftPlayer#getProfile`) and authlib 1.5.16 exactly; the bodies only
serve tests. This source set is never packaged: the running server provides the real classes, and
`verifyBackendJar` fails if any of them reaches the backend jar.

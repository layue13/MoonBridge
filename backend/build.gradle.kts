import java.io.DataInputStream
import java.util.jar.JarFile

plugins {
    id("moonbridge.java8-library-conventions")
    id("moonbridge.publishing-conventions")
}

val spigotApi = "org.spigotmc:spigot-api:1.8-R0.1-SNAPSHOT"

// Compile-only copies of the server classes the compiled profile adapters call (see src/serverStubs/README.md).
// The running server provides the real ones, so the stubs are never packaged.
val serverStubs = sourceSets.create("serverStubs")

dependencies {
    compileOnly(serverStubs.output)
    testImplementation(serverStubs.output)
    api(project(":messaging"))
    // The running server supplies Bukkit; business plugins compile against their own server API.
    compileOnly(spigotApi) { isTransitive = false }
    testImplementation(spigotApi) { isTransitive = false }
    // Plugin's legacy API signature references Ebean; needed by the test proxy only.
    testRuntimeOnly("org.avaje:ebean:2.8.1") { isTransitive = false }
}

moonbridgePublication {
    artifactId.set("backend")
    displayName.set("MoonBridge Backend")
    description.set("Java 8 backend side of MoonBridge: the shared Bukkit host plugin (MoonBridgeBackend), its "
            + "service API and the control channel client for other hosts.")
}

// The jar is the deployable Bukkit plugin and the one copy of the messaging classes on a server.
// Business plugins use it compileOnly plus plugin.yml depend, so Bukkit resolves one service class identity.
tasks.jar {
    archiveBaseName.set("moonbridge-backend")
    dependsOn(configurations.runtimeClasspath)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ configurations.runtimeClasspath.get().filter { it.extension == "jar" }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

val verifyBackendJar = tasks.register("verifyBackendJar") {
    group = "verification"
    dependsOn(tasks.jar)
    val backendJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(backendJar)
    doLast {
        JarFile(backendJar.get().asFile).use { archive ->
            val entries = archive.entries().asSequence().toList()
            val names = entries.map { it.name }
            listOf("plugin.yml", "config.yml",
                "dev/moonbridge/bukkit/BukkitMessagingService.class",
                "dev/moonbridge/bukkit/BukkitSessionService.class",
                "dev/moonbridge/bukkit/BackendPlayerSession.class",
                "dev/moonbridge/messaging/Message.class",
                "dev/moonbridge/messaging/internal/LocalMessaging.class",
                "dev/moonbridge/messaging/protocol/MessageCodec.class",
                "dev/moonbridge/messaging/session/ForwardedSessionProof.class",
                "dev/moonbridge/messaging/session/ForwardedSessionProof\$Claims.class",
                "dev/moonbridge/backendchannel/BackendChannelClient.class",
                "dev/moonbridge/backendchannel/Wire\$RegisteredIdentity.class").forEach {
                check(names.count { name -> name == it } == 1) { "Backend jar must contain exactly one $it" }
            }
            check(names.none { it.startsWith("org/bukkit/") }) { "Bukkit API must remain server-provided" }
            check(names.none { it.startsWith("com/mojang/") }) { "authlib must remain server-provided" }
            entries.filter { it.name.endsWith(".class") }.forEach { entry ->
                DataInputStream(archive.getInputStream(entry)).use { input ->
                    input.readInt()
                    input.readUnsignedShort()
                    check(input.readUnsignedShort() <= 52) { "Backend class is newer than Java 8: ${entry.name}" }
                }
            }
        }
    }
}

tasks.named("check") { dependsOn(verifyBackendJar) }

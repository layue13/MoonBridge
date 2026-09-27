import java.io.DataInputStream
import java.util.jar.JarFile

plugins {
    `java-library`
    `maven-publish`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

dependencies {
    api(project(":backend-bukkit-api"))
    implementation(project(":backend-channel-client"))
    compileOnly("org.spigotmc:spigot-api:1.8-R0.1-SNAPSHOT") { isTransitive = false }
    testImplementation("org.spigotmc:spigot-api:1.8-R0.1-SNAPSHOT") { isTransitive = false }
    // Plugin's legacy API signature references Ebean; needed by the test proxy only.
    testRuntimeOnly("org.avaje:ebean:2.8.1") { isTransitive = false }
    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// One provider owns the API classes, codec and transport. Consumers use compileOnly
// and plugin.yml depend, so Bukkit resolves the same service class identity.
tasks.jar {
    archiveBaseName.set("moonbridge-backend-bukkit")
    dependsOn(configurations.runtimeClasspath)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ configurations.runtimeClasspath.get().filter { it.extension == "jar" }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

val verifyHostJar = tasks.register("verifyHostJar") {
    group = "verification"
    dependsOn(tasks.jar)
    val hostJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(hostJar)
    doLast {
        JarFile(hostJar.get().asFile).use { archive ->
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
                check(names.count { name -> name == it } == 1) { "Host jar must contain exactly one $it" }
            }
            check(names.none { it.startsWith("org/bukkit/") }) { "Bukkit API must remain server-provided" }
            entries.filter { it.name.endsWith(".class") }.forEach { entry ->
                DataInputStream(archive.getInputStream(entry)).use { input ->
                    input.readInt()
                    input.readUnsignedShort()
                    check(input.readUnsignedShort() <= 52) { "Host class is newer than Java 8: ${entry.name}" }
                }
            }
        }
    }
}

tasks.named("check") { dependsOn(verifyHostJar) }

publishing {
    publications {
        create<MavenPublication>("bukkitHost") {
            from(components["java"])
            artifactId = "backend-bukkit"
            pom {
                name.set("MoonBridge Backend Host")
                description.set("Java 8 shared channel messaging service for Bukkit and Uranium plugins.")
            }
        }
    }
    repositories {
        maven {
            name = "giteaPackages"
            url = uri(providers.environmentVariable("MAVEN_URL")
                .orElse("https://git.nest.potatolab.uk:8443/api/packages/layue13/maven").get())
            credentials {
                username = providers.environmentVariable("MAVEN_USER").orElse("NONE").get()
                password = providers.environmentVariable("MAVEN_PASSWORD").orElse("NONE").get()
            }
        }
    }
}

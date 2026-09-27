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
    api(project(":messaging-api"))
    // Consumers compile with their server API; the running server supplies Bukkit.
    compileOnly("org.spigotmc:spigot-api:1.8-R0.1-SNAPSHOT") { isTransitive = false }
}

val verifyApiJar = tasks.register("verifyApiJar") {
    group = "verification"
    dependsOn(tasks.jar)
    val apiJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(apiJar)
    doLast {
        JarFile(apiJar.get().asFile).use { archive ->
            val entries = archive.entries().asSequence().toList()
            val classes = entries.filter { it.name.endsWith(".class") }
            check(classes.map { it.name }.toSet() == setOf(
                "dev/moonbridge/bukkit/BackendPlayerSession.class",
                "dev/moonbridge/bukkit/BukkitMessagingService.class",
                "dev/moonbridge/bukkit/BukkitSessionService.class")) {
                "Bukkit API artifact must contain only the public service contract"
            }
            check(entries.none { it.name == "plugin.yml" }) { "API is not a deployable plugin" }
            classes.forEach { entry ->
                DataInputStream(archive.getInputStream(entry)).use { input ->
                    input.readInt()
                    input.readUnsignedShort()
                    check(input.readUnsignedShort() == 52) { "Bukkit API must target Java 8" }
                }
            }
        }
    }
}

tasks.named("check") { dependsOn(verifyApiJar) }

publishing {
    publications {
        create<MavenPublication>("bukkitApi") {
            from(components["java"])
            artifactId = "backend-bukkit-api"
            pom {
                name.set("MoonBridge Bukkit API")
                description.set("Java 8 service contract for plugins using the shared MoonBridge backend host.")
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

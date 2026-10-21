plugins {
    id("moonbridge.java-library-conventions") apply false
    id("moonbridge.application-conventions") apply false
}

val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

allprojects {
    group = "uk.potatolab.moonbridge"
    version = releaseVersion
}

val luckPerms = findProject(":luckperms-moonbridge")
val published = listOfNotNull(":proxy-plugin-api", ":messaging", ":backend", luckPerms?.path)

tasks.register("check") {
    group = "verification"
    description = "Runs every module's checks (add -Pluckperms to include the LuckPerms plugin)."
    dependsOn(subprojects.map { "${it.path}:check" })
}

tasks.register("publish") {
    group = "publishing"
    description = "Publishes the Maven modules (CI passes -Pluckperms so the LuckPerms plugin is included)."
    dependsOn(published.map { "$it:publish" })
}

// client-mod/ is a separate Gradle build (GTNH toolchain for Minecraft 1.7.10); release runs it through its
// own wrapper. Pass -PskipClientMod to leave it out, e.g. where the Minecraft toolchain cannot be downloaded.
val skipClientMod = providers.gradleProperty("skipClientMod").map { it != "false" }.getOrElse(false)
val windows = System.getProperty("os.name").lowercase().contains("windows")

val buildClientMod = tasks.register<Exec>("buildClientMod") {
    group = "build"
    description = "Builds the 1.7.10 Forge WebSocket client mod in client-mod/."
    workingDir = file("client-mod")
    // clean: the jar name carries the git version, so older jars would otherwise be collected too.
    val gradle = if (windows) listOf("cmd", "/c", "gradlew.bat") else listOf("bash", "./gradlew")
    commandLine(gradle + listOf("clean", "build"))
}

tasks.register<Sync>("release") {
    group = "distribution"
    description = "Collects everything a deployment needs into build/release (see docs/release-layout.md)."
    into(layout.buildDirectory.dir("release"))
    from(file("docs/release-layout.md")) { rename { "README.md" } }
    from(project(":proxy-core").tasks.named("distZip")) { into("server") }
    from(project(":backend").tasks.named("jar")) { into("backend") }
    if (!skipClientMod) {
        dependsOn(buildClientMod)
        from(fileTree("client-mod/build/libs") {
            include("moonbridgews-*.jar")
            exclude("*-dev.jar", "*-sources.jar")
        }) { into("client") }
    }
}

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

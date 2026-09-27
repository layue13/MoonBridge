plugins {
    id("moonbridge.java-library-conventions") apply false
    id("moonbridge.application-conventions") apply false
}

val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

allprojects {
    group = "uk.potatolab.moonbridge"
    version = releaseVersion
}

tasks.register("check") {
    group = "verification"
    dependsOn(":proxy-plugin-api:check", ":messaging-api:check", ":messaging-protocol:check",
        ":backend-channel-client:check", ":backend-bukkit-api:check", ":backend-bukkit:check", ":proxy-core:check",
        ":luckperms-moonbridge:check")
}

tasks.register("publish") {
    group = "publishing"
    dependsOn(":proxy-plugin-api:publish", ":messaging-api:publish", ":messaging-protocol:publish",
        ":backend-channel-client:publish", ":backend-bukkit-api:publish", ":backend-bukkit:publish", ":luckperms-moonbridge:publish")
}

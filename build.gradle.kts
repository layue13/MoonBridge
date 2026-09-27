plugins {
    id("moonbridge.java-library-conventions") apply false
    id("moonbridge.application-conventions") apply false
}

allprojects {
    group = "uk.potatolab"
    version = "0.1.0-SNAPSHOT"
}

tasks.register("check") {
    group = "verification"
    dependsOn(":proxy-plugin-api:check", ":messaging-api:check", ":messaging-protocol:check",
        ":backend-channel-client:check", ":backend-bukkit-api:check", ":backend-bukkit:check", ":proxy-core:check")
}

tasks.register("publish") {
    group = "publishing"
    dependsOn(":proxy-plugin-api:publish", ":messaging-api:publish", ":messaging-protocol:publish",
        ":backend-channel-client:publish", ":backend-bukkit-api:publish", ":backend-bukkit:publish")
}

plugins {
    id("strataproxy.java-library-conventions") apply false
    id("strataproxy.application-conventions") apply false
}

allprojects {
    group = "uk.potatolab"
    version = "0.1.0-SNAPSHOT"
}

tasks.register("check") {
    group = "verification"
    dependsOn(":proxy-plugin-api:check", ":messaging-api:check", ":messaging-protocol:check",
        ":backend-channel-client:check", ":backend-channel-bukkit:check", ":proxy-core:check")
}

tasks.register("publish") {
    group = "publishing"
    dependsOn(":proxy-plugin-api:publish", ":messaging-api:publish", ":messaging-protocol:publish",
        ":backend-channel-client:publish", ":backend-channel-bukkit:publish")
}

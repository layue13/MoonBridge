plugins {
    id("moonbridge.java8-library-conventions")
    id("moonbridge.publishing-conventions")
}

moonbridgePublication {
    artifactId.set("messaging")
    displayName.set("MoonBridge Messaging")
    description.set("Java 8-compatible message envelope, channel API and wire codec shared by the proxy and backends.")
}

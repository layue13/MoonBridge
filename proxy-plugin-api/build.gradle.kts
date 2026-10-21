plugins {
    id("moonbridge.java-library-conventions")
    id("moonbridge.publishing-conventions")
}

dependencies {
    api(project(":messaging"))
    api(libs.findLibrary("slf4j-api").get())
    api(libs.findLibrary("adventure-api").get())
    api(libs.findLibrary("adventure-minimessage").get())
}

moonbridgePublication {
    artifactId.set("proxy-plugin-api")
    displayName.set("MoonBridge Plugin API")
    description.set("Stable API for MoonBridge proxy-side plugins.")
}

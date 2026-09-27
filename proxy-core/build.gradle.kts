import org.gradle.api.tasks.Exec

plugins {
    id("moonbridge.application-conventions")
}

val runtimeJavaHome = System.getProperty("java.home")

dependencies {
    implementation(project(":messaging-protocol"))
    // The plugin contract is the only stable external boundary. Everything else
    // in this project remains an implementation package of the proxy core.
    api(project(":proxy-plugin-api"))
    implementation(libs.findLibrary("slf4j-api").get())
    implementation(libs.findLibrary("netty-codec").get())
    implementation(libs.findLibrary("netty-transport").get())
    implementation(libs.findLibrary("netty-handler").get())
    implementation(libs.findLibrary("netty-resolver-dns").get())
    implementation(libs.findLibrary("jackson-databind").get())
    implementation(libs.findLibrary("jackson-yaml").get())
    implementation(libs.findLibrary("adventure-gson").get())
    implementation(libs.findLibrary("adventure-plain").get())
    runtimeOnly(libs.findLibrary("logback-classic").get())
    testImplementation(project(":backend-channel-client"))
}

val installedDistSmokeTest = tasks.register<Exec>("installedDistSmokeTest") {
    group = "verification"
    description = "Runs the installed MoonBridge distribution in --validate-config mode."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/moonbridge")
    inputs.dir(installDir)
    environment("JAVA_HOME", runtimeJavaHome)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "moonbridge.bat" else "moonbridge").asFile
        val config = installDir.get().file("config/moonbridge.yml").asFile
        commandLine(script.absolutePath, "--validate-config", config.absolutePath)
    }
}

val installedDistHelpSmokeTest = tasks.register<Exec>("installedDistHelpSmokeTest") {
    group = "verification"
    description = "Runs the installed MoonBridge distribution help command."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/moonbridge")
    inputs.dir(installDir)
    environment("JAVA_HOME", runtimeJavaHome)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "moonbridge.bat" else "moonbridge").asFile
        commandLine(script.absolutePath, "--help")
    }
}

val installedDistVersionSmokeTest = tasks.register<Exec>("installedDistVersionSmokeTest") {
    group = "verification"
    description = "Runs the installed MoonBridge distribution version command."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/moonbridge")
    inputs.dir(installDir)
    environment("JAVA_HOME", runtimeJavaHome)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "moonbridge.bat" else "moonbridge").asFile
        commandLine(script.absolutePath, "--version")
    }
}

tasks.named("check") {
    dependsOn(installedDistSmokeTest)
    dependsOn(installedDistHelpSmokeTest)
    dependsOn(installedDistVersionSmokeTest)
}

application {
    mainClass.set("dev.moonbridge.app.ProxyMain")
    applicationName = "moonbridge"
}

distributions {
    main {
        contents {
            from("src/main/resources/config") {
                into("config")
            }
            from(project(":backend-channel-client").tasks.named("jar")) {
                into("backend-client")
            }
            from(project(":messaging-api").tasks.named("jar")) {
                into("backend-client")
            }
            from(project(":messaging-protocol").tasks.named("jar")) {
                into("backend-client")
            }
            from(project(":backend-bukkit").tasks.named("jar")) {
                into("backend-host")
            }
            from(listOf(project(":backend-bukkit-api").tasks.named("jar"),
                project(":messaging-api").tasks.named("jar"))) {
                into("backend-api")
            }
        }
    }
}

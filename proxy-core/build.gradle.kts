import org.gradle.api.tasks.Exec

plugins {
    id("strataproxy.application-conventions")
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
    description = "Runs the installed StrataProxy distribution in --validate-config mode."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/strataproxy")
    inputs.dir(installDir)
    environment("JAVA_HOME", runtimeJavaHome)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "strataproxy.bat" else "strataproxy").asFile
        val config = installDir.get().file("config/strataproxy.yml").asFile
        commandLine(script.absolutePath, "--validate-config", config.absolutePath)
    }
}

val installedDistHelpSmokeTest = tasks.register<Exec>("installedDistHelpSmokeTest") {
    group = "verification"
    description = "Runs the installed StrataProxy distribution help command."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/strataproxy")
    inputs.dir(installDir)
    environment("JAVA_HOME", runtimeJavaHome)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "strataproxy.bat" else "strataproxy").asFile
        commandLine(script.absolutePath, "--help")
    }
}

val installedDistVersionSmokeTest = tasks.register<Exec>("installedDistVersionSmokeTest") {
    group = "verification"
    description = "Runs the installed StrataProxy distribution version command."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/strataproxy")
    inputs.dir(installDir)
    environment("JAVA_HOME", runtimeJavaHome)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "strataproxy.bat" else "strataproxy").asFile
        commandLine(script.absolutePath, "--version")
    }
}

tasks.named("check") {
    dependsOn(installedDistSmokeTest)
    dependsOn(installedDistHelpSmokeTest)
    dependsOn(installedDistVersionSmokeTest)
}

application {
    mainClass.set("dev.strataproxy.app.ProxyMain")
    applicationName = "strataproxy"
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
            from(project(":backend-channel-bukkit").tasks.named("jar")) {
                into("backend-host")
            }
        }
    }
}

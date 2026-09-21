import org.gradle.api.tasks.Exec

plugins {
    id("strataproxy.application-conventions")
}

dependencies {
    implementation(project(":proxy-core"))
    implementation(project(":proxy-plugin"))
    implementation(libs.findLibrary("jackson-databind").get())
    implementation(libs.findLibrary("jackson-yaml").get())
    runtimeOnly(libs.findLibrary("slf4j-simple").get())
}

val installedDistSmokeTest = tasks.register<Exec>("installedDistSmokeTest") {
    group = "verification"
    description = "Runs the installed StrataProxy distribution in --validate-config mode."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/strataproxy")
    inputs.dir(installDir)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "strataproxy.bat" else "strataproxy").asFile
        val config = installDir.get().file("config/strataproxy.yml").asFile
        commandLine(script.absolutePath, "--validate-config", config.absolutePath)
    }
}

val installedDistProductionConfigSmokeTest = tasks.register<Exec>("installedDistProductionConfigSmokeTest") {
    group = "verification"
    description = "Validates the installed production StrataProxy config."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/strataproxy")
    inputs.dir(installDir)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "strataproxy.bat" else "strataproxy").asFile
        val config = installDir.get().file("config/strataproxy-production.yml").asFile
        commandLine(script.absolutePath, "--validate-config", config.absolutePath)
    }
}

val installedDistHelpSmokeTest = tasks.register<Exec>("installedDistHelpSmokeTest") {
    group = "verification"
    description = "Runs the installed StrataProxy distribution help command."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/strataproxy")
    inputs.dir(installDir)

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

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "strataproxy.bat" else "strataproxy").asFile
        commandLine(script.absolutePath, "--version")
    }
}

tasks.named("check") {
    dependsOn(installedDistSmokeTest)
    dependsOn(installedDistProductionConfigSmokeTest)
    dependsOn(installedDistHelpSmokeTest)
    dependsOn(installedDistVersionSmokeTest)
}

application {
    mainClass.set("dev.strataproxy.app.StrataProxyLauncher")
    applicationName = "strataproxy"
}

distributions {
    main {
        contents {
            from("src/main/resources/config") {
                into("config")
            }
            from("../deployment") {
                into("deployment")
            }
        }
    }
}

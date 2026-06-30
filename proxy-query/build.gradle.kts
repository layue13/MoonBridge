import org.gradle.api.tasks.Exec

plugins {
    id("strataproxy.application-conventions")
}

dependencies {
    api(project(":proxy-api"))
    implementation(libs.findLibrary("cli-picocli").get())
}

application {
    mainClass.set("dev.strataproxy.query.StrataProxyQueryCli")
    applicationName = "strataproxy-query"
}

val installedDistSmokeTest = tasks.register<Exec>("installedDistSmokeTest") {
    group = "verification"
    description = "Runs the installed strataproxy-query distribution help command."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/strataproxy-query")
    inputs.dir(installDir)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "strataproxy-query.bat" else "strataproxy-query").asFile
        commandLine(script.absolutePath, "--help")
    }
}

tasks.named("check") {
    dependsOn(installedDistSmokeTest)
}

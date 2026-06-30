import org.gradle.api.tasks.Exec

plugins {
    id("strataproxy.application-conventions")
}

dependencies {
    implementation(project(":proxy-admin-api"))
    implementation(libs.findLibrary("jackson-databind").get())
    implementation(libs.findLibrary("cli-picocli").get())
    testImplementation(project(":proxy-registry"))
}

application {
    mainClass.set("dev.strataproxy.admin.cli.StrataProxyAdminCli")
    applicationName = "strataproxy-admin"
}

val installedDistSmokeTest = tasks.register<Exec>("installedDistSmokeTest") {
    group = "verification"
    description = "Runs the installed strataproxy-admin distribution help command."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/strataproxy-admin")
    inputs.dir(installDir)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "strataproxy-admin.bat" else "strataproxy-admin").asFile
        commandLine(script.absolutePath, "--help")
    }
}

tasks.named("check") {
    dependsOn(installedDistSmokeTest)
}

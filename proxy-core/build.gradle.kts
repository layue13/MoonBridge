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
    // Native transports are optional at runtime: NetworkTransport falls back to NIO when the
    // platform library is missing or cannot load (Windows, other CPU architectures, musl).
    implementation(libs.findLibrary("netty-transport-classes-epoll").get())
    implementation(libs.findLibrary("netty-transport-classes-kqueue").get())
    for (classifier in listOf("linux-x86_64", "linux-aarch_64")) {
        runtimeOnly(variantOf(libs.findLibrary("netty-transport-native-epoll").get()) { classifier(classifier) })
    }
    for (classifier in listOf("osx-x86_64", "osx-aarch_64")) {
        runtimeOnly(variantOf(libs.findLibrary("netty-transport-native-kqueue").get()) { classifier(classifier) })
    }
    implementation(libs.findLibrary("jackson-databind").get())
    implementation(libs.findLibrary("jackson-yaml").get())
    implementation(libs.findLibrary("adventure-gson").get())
    implementation(libs.findLibrary("adventure-plain").get())
    runtimeOnly(libs.findLibrary("logback-classic").get())
    testImplementation(project(":backend-channel-client"))
    testImplementation(libs.findLibrary("logback-classic").get())
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

val compilePermissionAcceptance = tasks.register<JavaCompile>("compilePermissionAcceptance") {
    source(rootProject.file("smoke/PermissionAcceptance.java"))
    classpath = sourceSets.main.get().runtimeClasspath
    destinationDirectory.set(layout.buildDirectory.dir("classes/permissionAcceptance"))
    options.release.set(25)
}

val permissionAcceptance = tasks.register<JavaExec>("permissionAcceptance") {
    group = "verification"
    description = "Loads the packaged LuckPerms plugin and verifies native permissions, commands and H2 persistence."
    dependsOn(tasks.named("installDist"), compilePermissionAcceptance)
    classpath = sourceSets.main.get().runtimeClasspath + files(compilePermissionAcceptance.flatMap { it.destinationDirectory })
    mainClass.set("PermissionAcceptance")
    args(layout.buildDirectory.dir("install/moonbridge").get().asFile.absolutePath,
        layout.buildDirectory.dir("permission-acceptance").get().asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(installedDistSmokeTest)
    dependsOn(installedDistHelpSmokeTest)
    dependsOn(installedDistVersionSmokeTest)
    dependsOn(permissionAcceptance)
}

application {
    mainClass.set("dev.moonbridge.app.ProxyMain")
    applicationName = "moonbridge"
    // Netty's epoll/kqueue transports load a JNI library; allow it without JDK restricted-method warnings.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Tests track every buffer so LeakGate can fail on a leak; the distribution runs with detection off.
    systemProperty("io.netty.leakDetection.level", "paranoid")
    systemProperty("io.netty.leakDetection.targetRecords", "16")
}

tasks.jar {
    manifest.attributes("Implementation-Title" to "MoonBridge", "Implementation-Version" to project.version)
}

distributions {
    main {
        contents {
            from(rootProject.file("README.md"))
            from(project(":luckperms-moonbridge").tasks.named("pluginJar")) {
                into("plugins")
            }
            from(rootProject.file("docs")) {
                into("docs")
            }
            from(rootProject.file("smoke/results")) {
                into("smoke/results")
            }
            from(rootProject.file("benchmarks/results")) {
                into("benchmarks/results")
            }
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

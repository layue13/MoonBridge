import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.Exec

plugins {
    id("moonbridge.java-library-conventions")
    id("moonbridge.publishing-conventions")
    distribution
    id("com.gradleup.shadow") version "9.6.1"
}

val runtimeJavaHome = System.getProperty("java.home")

dependencies {
    implementation(project(":messaging"))
    // The plugin contract is the only stable external boundary. Everything else
    // in this project remains an implementation package of the proxy core.
    api(project(":proxy-plugin-api"))
    implementation(libs.findLibrary("slf4j-api").get())
    implementation(libs.findLibrary("netty-codec").get())
    implementation(libs.findLibrary("netty-transport").get())
    implementation(libs.findLibrary("netty-handler").get())
    implementation(libs.findLibrary("netty-codec-http").get())
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
    // BoringSSL for wss:// listeners; Netty uses it when it loads and falls back to the JDK provider otherwise.
    for (classifier in listOf("linux-x86_64", "linux-aarch_64", "osx-x86_64", "osx-aarch_64", "windows-x86_64")) {
        runtimeOnly(variantOf(libs.findLibrary("netty-tcnative-boringssl-static").get()) { classifier(classifier) })
    }
    implementation(libs.findLibrary("jackson-databind").get())
    implementation(libs.findLibrary("jackson-yaml").get())
    implementation(libs.findLibrary("adventure-gson").get())
    implementation(libs.findLibrary("adventure-plain").get())
    // Compile-time: the console log appender and the loglevel command use Logback directly.
    implementation(libs.findLibrary("logback-classic").get())
    // The admin console: line editing, history and completion. The FFM provider talks to the terminal through
    // Java's foreign function API, allowed by the jar manifest's Enable-Native-Access.
    implementation(libs.findLibrary("jline-reader").get())
    implementation(libs.findLibrary("jline-terminal").get())
    runtimeOnly(libs.findLibrary("jline-terminal-ffm").get())
    testImplementation(project(":backend"))
    testImplementation(libs.findLibrary("logback-classic").get())
    testImplementation(libs.findLibrary("netty-pkitesting").get())
}

// The distribution is started the way an operator starts it: java -jar moonbridge.jar from the install directory.
fun installedDistCheck(name: String, description: String, vararg arguments: String) =
    tasks.register<Exec>(name) {
        group = "verification"
        this.description = description
        dependsOn(tasks.named("installDist"))

        val installDir = layout.buildDirectory.dir("install/moonbridge")
        inputs.dir(installDir)
        workingDir(installDir)
        commandLine(File(runtimeJavaHome, "bin/java").absolutePath, "-jar", "moonbridge.jar", *arguments)
    }

val installedDistSmokeTest = installedDistCheck("installedDistSmokeTest",
    "Runs the installed MoonBridge distribution in --validate-config mode.",
    "--validate-config", "config/moonbridge.yml")
val installedDistHelpSmokeTest = installedDistCheck("installedDistHelpSmokeTest",
    "Runs the installed MoonBridge distribution help command.", "--help")
val installedDistVersionSmokeTest = installedDistCheck("installedDistVersionSmokeTest",
    "Runs the installed MoonBridge distribution version command.", "--version")

// The benchmarks in testing/benchmarks/ declare proxy-core packages to reach package-private classes and are
// normally compiled by their PowerShell runners. Compiling them here keeps them building with main.
val compileBenchmarks = tasks.register<JavaCompile>("compileBenchmarks") {
    group = "verification"
    description = "Compiles the benchmarks in testing/benchmarks/ against proxy-core."
    source(rootProject.fileTree("testing/benchmarks") { include("*.java") })
    classpath = sourceSets.main.get().runtimeClasspath
    destinationDirectory.set(layout.buildDirectory.dir("classes/benchmarks"))
    options.release.set(25)
}

// permissionAcceptance needs the LuckPerms plugin, which is only built with -Pluckperms.
val luckPerms = rootProject.findProject(":luckperms-moonbridge")
if (luckPerms != null) {
    val compilePermissionAcceptance = tasks.register<JavaCompile>("compilePermissionAcceptance") {
        source(rootProject.file("testing/smoke/PermissionAcceptance.java"))
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
        systemProperty("moonbridge.logDir", layout.buildDirectory.dir("permission-acceptance/logs").get().asFile.absolutePath)
        args(layout.buildDirectory.dir("install/moonbridge").get().asFile.absolutePath,
            layout.buildDirectory.dir("permission-acceptance").get().asFile.absolutePath)
    }

    tasks.named("check") { dependsOn(permissionAcceptance) }
}

tasks.named("check") {
    dependsOn(installedDistSmokeTest)
    dependsOn(installedDistHelpSmokeTest)
    dependsOn(installedDistVersionSmokeTest)
    dependsOn(compileBenchmarks)
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

// The server ships as one runnable jar, like other Minecraft proxies: java -jar moonbridge.jar.
val serverJar = tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set("moonbridge.jar")
    // Netty, Jackson and Logback register implementations through META-INF/services.
    mergeServiceFiles()
    exclude("module-info.class", "META-INF/versions/*/module-info.class",
        "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest.attributes(
        "Main-Class" to "dev.moonbridge.app.ProxyMain",
        "Implementation-Title" to "MoonBridge",
        "Implementation-Version" to project.version,
        // Jackson ships version-specific classes; keep them selected at runtime.
        "Multi-Release" to "true",
        // Netty's epoll/kqueue transports load a JNI library; allow it without restricted-method warnings.
        "Enable-Native-Access" to "ALL-UNNAMED",
    )
}

distributions {
    main {
        distributionBaseName.set("moonbridge")
        // Only what the proxy needs to run. Docs and test records stay in the repository, and the
        // backend jar ships separately in build/release/backend (see docs/release-layout.md).
        contents {
            from(serverJar)
            from(rootProject.file("README.md"))
            if (luckPerms != null) {
                from(luckPerms.tasks.named("pluginJar")) {
                    into("plugins")
                }
            }
            from("src/main/resources/config") {
                into("config")
            }
        }
    }
}

// The proxy distribution (the zip `release` puts under server/) is published too, as uk.potatolab.moonbridge:
// moonbridge-server:<version>@zip, so a deployment bundle (the TDLM modpack's proxy pack) can fetch it like any module.
publishing {
    publications {
        create<MavenPublication>("server") {
            artifactId = "moonbridge-server"
            artifact(tasks.named("distZip"))
        }
    }
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("moonbridge.java-library-conventions")
    id("com.gradleup.shadow") version "9.6.1"
    `maven-publish`
}

val hostBridgeCompileClasspath = configurations.create("hostBridgeCompileClasspath") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val hostBridgeOutput = layout.buildDirectory.dir("classes/java/hostBridge")
val compileHostBridgeJava = tasks.register<JavaCompile>("compileHostBridgeJava") {
    group = "build"
    description = "Compiles the unshaded Adventure bridge against MoonBridge's host version."
    source(fileTree("src/bridge/java") { include("**/*.java") })
    classpath = hostBridgeCompileClasspath
    destinationDirectory.set(hostBridgeOutput)
    options.release.set(25)
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly(project(":proxy-plugin-api")) {
        // The platform engine must compile against LuckPerms' relocated Adventure 4.x.
        // The unshaded host Adventure 5.x API is used only by the bridge source set.
        exclude(group = "net.kyori")
    }
    implementation("me.lucko.luckperms:common:5.5-SNAPSHOT")
    implementation("me.lucko.luckperms:loader-utils:5.5-SNAPSHOT")
    add(hostBridgeCompileClasspath.name, project(":proxy-plugin-api"))
    add(hostBridgeCompileClasspath.name, libs.findLibrary("adventure-gson").get())
    add(hostBridgeCompileClasspath.name, libs.findLibrary("adventure-plain").get())
    testImplementation(project(":proxy-core"))
    testRuntimeOnly(libs.findLibrary("adventure-gson").get())
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(compileHostBridgeJava)
    classpath += files(hostBridgeOutput)
}

tasks.named<JavaCompile>("compileTestJava") {
    dependsOn(compileHostBridgeJava)
    classpath += files(hostBridgeOutput)
    classpath += hostBridgeCompileClasspath
}

tasks.test {
    dependsOn(compileHostBridgeJava)
    classpath += files(hostBridgeOutput)
    classpath += hostBridgeCompileClasspath
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("engine")
    archiveBaseName.set("luckperms-moonbridge")

    dependencies {
        include(dependency("me.lucko.luckperms:.*"))
        include(dependency("net.luckperms:.*"))
        // Unlike Velocity, MoonBridge does not provide Guava to plugins.
        include(dependency("com.google.guava:guava"))
    }

    relocate("net.kyori.event", "me.lucko.luckperms.lib.eventbus")
    relocate("com.github.benmanes.caffeine", "me.lucko.luckperms.lib.caffeine")
    relocate("okio", "me.lucko.luckperms.lib.okio")
    relocate("okhttp3", "me.lucko.luckperms.lib.okhttp3")
    relocate("net.bytebuddy", "me.lucko.luckperms.lib.bytebuddy")
    relocate("me.lucko.commodore", "me.lucko.luckperms.lib.commodore")
    relocate("org.mariadb.jdbc", "me.lucko.luckperms.lib.mariadb")
    relocate("com.mysql", "me.lucko.luckperms.lib.mysql")
    relocate("org.postgresql", "me.lucko.luckperms.lib.postgresql")
    relocate("com.zaxxer.hikari", "me.lucko.luckperms.lib.hikari")
    relocate("com.mongodb", "me.lucko.luckperms.lib.mongodb")
    relocate("org.bson", "me.lucko.luckperms.lib.bson")
    relocate("redis.clients.jedis", "me.lucko.luckperms.lib.jedis")
    relocate("io.nats.client", "me.lucko.luckperms.lib.nats")
    relocate("com.rabbitmq", "me.lucko.luckperms.lib.rabbitmq")
    relocate("org.apache.commons.pool2", "me.lucko.luckperms.lib.commonspool2")
    relocate("net.kyori.adventure", "me.lucko.luckperms.lib.adventure")
    relocate("ninja.leaping.configurate", "me.lucko.luckperms.lib.configurate")
    relocate("org.yaml.snakeyaml", "me.lucko.luckperms.lib.yaml")

    from(rootProject.file("vendor/luckperms/LICENSE.txt"))
    manifest {
        attributes(
            "Implementation-Title" to "LuckPerms MoonBridge Platform",
            "Implementation-Version" to project.version,
            "LuckPerms-Upstream-Revision" to "25f223317a9ec2b6e73369126b630eca07d79506",
        )
    }
}

tasks.jar { enabled = false }

val pluginJar = tasks.register<Jar>("pluginJar") {
    dependsOn(tasks.named("shadowJar"), compileHostBridgeJava)
    archiveBaseName.set("luckperms-moonbridge")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ zipTree(tasks.named<ShadowJar>("shadowJar").get().archiveFile) }) {
        exclude("META-INF/MANIFEST.MF")
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }
    from(hostBridgeOutput)
    manifest {
        attributes(
            "Implementation-Title" to "LuckPerms MoonBridge Platform",
            "Implementation-Version" to project.version,
            "LuckPerms-Upstream-Revision" to "25f223317a9ec2b6e73369126b630eca07d79506",
        )
    }
}

tasks.assemble {
    dependsOn(pluginJar)
}

publishing {
    publications {
        create<MavenPublication>("luckPermsPlugin") {
            artifact(pluginJar)
            artifactId = "luckperms-moonbridge"
            pom {
                name.set("LuckPerms MoonBridge Platform")
                description.set("Native LuckPerms platform plugin for MoonBridge. Install in the proxy plugins directory.")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/LuckPerms/LuckPerms/blob/master/LICENSE.txt")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "giteaPackages"
            url = uri(providers.environmentVariable("MAVEN_URL")
                .orElse("https://git.nest.potatolab.uk:8443/api/packages/layue13/maven").get())
            credentials {
                username = providers.environmentVariable("MAVEN_USER").orElse("NONE").get()
                password = providers.environmentVariable("MAVEN_PASSWORD").orElse("NONE").get()
            }
        }
    }
}

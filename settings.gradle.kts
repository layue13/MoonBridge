pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven {
            url = uri("https://repo.lucko.me/")
            content { includeGroup("me.lucko.configurate") }
        }
        maven {
            url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
            content { includeGroup("org.spigotmc") }
        }
    }
}

rootProject.name = "moonbridge"

// Project names (and so task paths and Maven artifact IDs) stay flat; the directories group them:
// proxy/ for the proxy side, messaging/ for the API shared with backends, backend/ for the Bukkit host.
fun module(name: String, dir: String) {
    include(name)
    project(":$name").projectDir = file(dir)
}

module("proxy-core", "proxy/core")
module("proxy-plugin-api", "proxy/plugin-api")
module("messaging", "messaging")
module("backend", "backend")

// The LuckPerms platform plugin compiles LuckPerms itself from the proxy/vendor/luckperms submodule, which is
// slow and pulls a large dependency tree, so it is opt-in: pass -Pluckperms (CI and release builds do).
if (providers.gradleProperty("luckperms").map { it != "false" }.getOrElse(false)) {
    check(file("proxy/vendor/luckperms/build.gradle").isFile) {
        "-Pluckperms needs the LuckPerms sources: run git submodule update --init --recursive"
    }
    includeBuild("proxy/luckperms/upstream-build")
    module("luckperms-moonbridge", "proxy/luckperms")
}

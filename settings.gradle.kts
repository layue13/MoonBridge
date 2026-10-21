pluginManagement {
    includeBuild("proxy-build-logic")
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

include(
    "proxy-core",
    "proxy-plugin-api",
    "messaging",
    "backend",
)

// The LuckPerms platform plugin compiles LuckPerms itself from the vendor/luckperms submodule, which is
// slow and pulls a large dependency tree, so it is opt-in: pass -Pluckperms (CI and release builds do).
if (providers.gradleProperty("luckperms").map { it != "false" }.getOrElse(false)) {
    check(file("vendor/luckperms/build.gradle").isFile) {
        "-Pluckperms needs the LuckPerms sources: run git submodule update --init --recursive"
    }
    includeBuild("luckperms-moonbridge/upstream-build")
    include("luckperms-moonbridge")
}

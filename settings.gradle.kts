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
            url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
            content { includeGroup("org.spigotmc") }
        }
    }
}

rootProject.name = "moonbridge"

include(
    "proxy-core",
    "proxy-plugin-api",
    "backend-channel-client",
    "messaging-api",
    "messaging-protocol",
    "backend-bukkit",
    "backend-bukkit-api",
)

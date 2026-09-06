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
    }
}

rootProject.name = "strataproxy"

include(
    "proxy-api",
    "proxy-bootstrap",
    "proxy-common",
    "proxy-plugin-api",
    "proxy-command",
    "proxy-plugin",
    "proxy-network",
    "proxy-protocol",
    "proxy-codec-minecraft",
    "proxy-registry",
    "proxy-routing",
    "proxy-compression",
    "proxy-observability",
    "proxy-packet-analysis",
    "proxy-admin-api",
    "proxy-admin-cli",
    "proxy-query",
    "proxy-native",
    "proxy-app",
)

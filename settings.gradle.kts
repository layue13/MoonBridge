pluginManagement {
    includeBuild("proxy-build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
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

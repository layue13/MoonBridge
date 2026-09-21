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
    "proxy-core",
    "proxy-plugin-api",
    "proxy-plugin",
    "proxy-app",
)

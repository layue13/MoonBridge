pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net/")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.lucko.me/")
        maven("https://libraries.minecraft.net/")
    }
    versionCatalogs {
        create("libs") {
            from(files("../../vendor/luckperms/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "luckperms-moonbridge-upstream"

include("api", "common", "common:loader-utils")
project(":api").projectDir = file("../../vendor/luckperms/api")
project(":common").projectDir = file("../../vendor/luckperms/common")
project(":common:loader-utils").projectDir = file("../../vendor/luckperms/common/loader-utils")

plugins {
    id("strataproxy.java-library-conventions")
    `maven-publish`
}

dependencies {
    api(libs.findLibrary("slf4j-api").get())
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("pluginApi") {
            from(components["java"])
            artifactId = "proxy-plugin-api"
            pom {
                name.set("StrataProxy Plugin API")
                description.set("Stable API for StrataProxy proxy-side plugins.")
            }
        }
    }
    repositories {
        maven {
            name = "giteaPackages"
            url = uri(providers.environmentVariable("MAVEN_URL")
                .orElse("https://git.nest.potatolab.uk:8443/api/packages/layue13/maven")
                .get())
            credentials {
                username = providers.environmentVariable("MAVEN_USER").orElse("NONE").get()
                password = providers.environmentVariable("MAVEN_PASSWORD").orElse("NONE").get()
            }
        }
    }
}

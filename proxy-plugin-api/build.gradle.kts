plugins {
    id("strataproxy.java-library-conventions")
    `maven-publish`
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
            name = "localPluginApi"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}

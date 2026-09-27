plugins {
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
    withJavadocJar()
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    api(project(":messaging-api"))
    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create<MavenPublication>("messagingProtocol") {
            from(components["java"])
            artifactId = "messaging-protocol"
            pom {
                name.set("MoonBridge Messaging Protocol")
                description.set("Java 8-compatible message wire codec shared by proxy and backend clients.")
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

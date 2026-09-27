plugins {
    `java-library`
    `maven-publish`
}

group = "uk.potatolab"
version = "0.1.0-SNAPSHOT"

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
    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create<MavenPublication>("messagingApi") {
            from(components["java"])
            artifactId = "messaging-api"
            pom {
                name.set("StrataProxy Messaging API")
                description.set("Java 8-compatible shared message envelope and channel API.")
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

plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("backendAgentApi") {
            from(components["java"])
            artifactId = "backend-agent-api"
            pom {
                name.set("StrataProxy Backend Agent API")
                description.set("Platform-independent plugin-message API for StrataProxy backend agents.")
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

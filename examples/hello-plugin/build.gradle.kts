plugins {
    `java-library`
}

group = "example.strataproxy"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    compileOnly("dev.strataproxy:proxy-plugin-api:0.1.0-SNAPSHOT")
}

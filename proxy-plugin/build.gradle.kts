plugins {
    id("strataproxy.java-library-conventions")
}

dependencies {
    api(project(":proxy-plugin-api"))
    api(libs.findLibrary("slf4j-api").get())
    testImplementation(project(":proxy-command"))
}

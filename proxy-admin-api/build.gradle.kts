plugins {
    id("strataproxy.java-library-conventions")
}

dependencies {
    api(project(":proxy-api"))
    api(project(":proxy-observability"))
    implementation(project(":proxy-observability"))
    implementation(project(":proxy-routing"))
    implementation(libs.findLibrary("jackson-databind").get())
    testImplementation(project(":proxy-bootstrap"))
    testImplementation(project(":proxy-registry"))
}

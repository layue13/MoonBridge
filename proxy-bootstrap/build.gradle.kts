plugins {
    id("strataproxy.java-library-conventions")
}

dependencies {
    api(project(":proxy-api"))
    implementation(project(":proxy-common"))
    implementation(libs.findLibrary("jackson-databind").get())
    implementation(libs.findLibrary("jackson-yaml").get())
}

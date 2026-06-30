plugins {
    id("strataproxy.java-library-conventions")
}

dependencies {
    api(project(":proxy-api"))
    implementation(project(":proxy-protocol"))
}

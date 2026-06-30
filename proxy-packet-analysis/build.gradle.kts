plugins {
    id("strataproxy.java-library-conventions")
}

dependencies {
    api(project(":proxy-api"))
    implementation(project(":proxy-codec-minecraft"))
    implementation(project(":proxy-protocol"))
}

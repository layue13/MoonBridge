plugins {
    id("strataproxy.java-library-conventions")
}

dependencies {
    api(project(":proxy-protocol"))
    implementation(libs.findLibrary("netty-codec").get())
}

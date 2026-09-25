plugins {
    id("strataproxy.java-library-conventions")
}

dependencies {
    implementation(project(":proxy-plugin-api"))
    implementation(libs.findLibrary("netty-resolver-dns").get())
}

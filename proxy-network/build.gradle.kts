plugins {
    id("strataproxy.java-library-conventions")
}

val nettyVersion = libs.findVersion("netty").get().requiredVersion

dependencies {
    api(project(":proxy-api"))
    api(project(":proxy-observability"))
    implementation(project(":proxy-codec-minecraft"))
    implementation(project(":proxy-compression"))
    implementation(project(":proxy-packet-analysis"))
    implementation(project(":proxy-protocol"))
    implementation(project(":proxy-routing"))
    implementation(libs.findLibrary("netty-transport").get())
    implementation(libs.findLibrary("netty-transport-classes-epoll").get())
    implementation(libs.findLibrary("netty-transport-classes-kqueue").get())
    implementation(libs.findLibrary("netty-handler").get())
    runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-x86_64")
    runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-aarch_64")
    runtimeOnly("io.netty:netty-transport-native-kqueue:$nettyVersion:osx-x86_64")
    runtimeOnly("io.netty:netty-transport-native-kqueue:$nettyVersion:osx-aarch_64")
}

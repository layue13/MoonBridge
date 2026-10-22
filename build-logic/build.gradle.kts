plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("javaLibraryConventions") {
            id = "moonbridge.java-library-conventions"
            implementationClass = "MoonBridgeJavaLibraryConventionsPlugin"
        }
        register("java8LibraryConventions") {
            id = "moonbridge.java8-library-conventions"
            implementationClass = "MoonBridgeJava8LibraryConventionsPlugin"
        }
        register("publishingConventions") {
            id = "moonbridge.publishing-conventions"
            implementationClass = "MoonBridgePublishingConventionsPlugin"
        }
    }
}

plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("javaLibraryConventions") {
            id = "moonbridge.java-library-conventions"
            implementationClass = "MoonBridgeJavaLibraryConventionsPlugin"
        }
        register("applicationConventions") {
            id = "moonbridge.application-conventions"
            implementationClass = "MoonBridgeApplicationConventionsPlugin"
        }
    }
}

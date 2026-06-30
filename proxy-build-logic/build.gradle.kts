plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("javaLibraryConventions") {
            id = "strataproxy.java-library-conventions"
            implementationClass = "StrataProxyJavaLibraryConventionsPlugin"
        }
        register("applicationConventions") {
            id = "strataproxy.application-conventions"
            implementationClass = "StrataProxyApplicationConventionsPlugin"
        }
    }
}

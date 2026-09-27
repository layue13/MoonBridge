import org.gradle.api.tasks.compile.JavaCompile

subprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")
}

allprojects {
    group = "me.lucko.luckperms"
    version = "5.5-SNAPSHOT"

    extensions.extraProperties["majorVersion"] = "5"
    extensions.extraProperties["minorVersion"] = "5"
    extensions.extraProperties["patchVersion"] = "85"
    extensions.extraProperties["apiVersion"] = "5.5"
    extensions.extraProperties["fullVersion"] = "5.5.85-moonbridge"

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(11)
    }
}

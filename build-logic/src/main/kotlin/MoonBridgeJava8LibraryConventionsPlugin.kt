import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.withType

/** Libraries loaded by Java 8 backends (Uranium and similar): compiled with --release 8. */
class MoonBridgeJava8LibraryConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply(MoonBridgeJavaLibraryConventionsPlugin::class.java)
        project.tasks.withType<JavaCompile>().configureEach {
            options.release.set(8)
            options.encoding = "UTF-8"
            // JDK 25 warns that --release 8 is obsolete; targeting Java 8 is the point of this convention.
            options.compilerArgs.add("-Xlint:-options")
        }
    }
}

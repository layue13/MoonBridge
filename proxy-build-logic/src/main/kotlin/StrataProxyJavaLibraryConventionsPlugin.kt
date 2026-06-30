import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.gradle.api.plugins.JavaPluginExtension

class StrataProxyJavaLibraryConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply(JavaLibraryPlugin::class.java)

        project.extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(25))
            }
        }

        project.tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        project.dependencies {
            "testImplementation"(project.dependencies.platform(project.libs.findLibrary("junit-bom").get()))
            "testImplementation"(project.libs.findLibrary("junit-jupiter").get())
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }
    }
}

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ApplicationPlugin

class MoonBridgeApplicationConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply(MoonBridgeJavaLibraryConventionsPlugin::class.java)
        project.plugins.apply(ApplicationPlugin::class.java)
    }
}

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ApplicationPlugin

class StrataProxyApplicationConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply(StrataProxyJavaLibraryConventionsPlugin::class.java)
        project.plugins.apply(ApplicationPlugin::class.java)
    }
}

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.findByType

/** Name and description of a module's standard Maven publication (its jar, sources and javadoc). */
abstract class MoonBridgePublicationExtension {
    abstract val artifactId: Property<String>
    abstract val displayName: Property<String>
    abstract val description: Property<String>
}

/**
 * Adds the shared package repository (and the organisation's Nexus when NEXUS_URL is set). A module that sets `moonbridgePublication.artifactId` also gets the
 * standard publication; a module with an unusual artifact (the LuckPerms plugin) declares its own instead.
 */
class MoonBridgePublishingConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply(MavenPublishPlugin::class.java)
        val publication = project.extensions.create<MoonBridgePublicationExtension>("moonbridgePublication")
        project.extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "giteaPackages"
                url = project.uri(project.providers.environmentVariable("MAVEN_URL")
                    .orElse("https://git.nest.potatolab.uk:8443/api/packages/layue13/maven").get())
                credentials {
                    username = project.providers.environmentVariable("MAVEN_USER").orElse("NONE").get()
                    password = project.providers.environmentVariable("MAVEN_PASSWORD").orElse("NONE").get()
                }
            }
            // Optional second destination (the TDLM organisation's Nexus, where the modpack build reads from).
            // Only added when NEXUS_URL is set, so builds without it publish exactly as before.
            val nexusUrl = project.providers.environmentVariable("NEXUS_URL").orNull
            if (!nexusUrl.isNullOrBlank()) {
                repositories.maven {
                    name = "nexus"
                    url = project.uri(nexusUrl)
                    credentials {
                        username = project.providers.environmentVariable("NEXUS_USER").orElse("NONE").get()
                        password = project.providers.environmentVariable("NEXUS_PASSWORD").orElse("NONE").get()
                    }
                }
            }
        }
        project.afterEvaluate {
            if (!publication.artifactId.isPresent) return@afterEvaluate
            project.extensions.findByType<JavaPluginExtension>()?.apply {
                withSourcesJar()
                withJavadocJar()
            }
            project.extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("maven") {
                    from(project.components.getByName("java"))
                    artifactId = publication.artifactId.get()
                    pom {
                        name.set(publication.displayName)
                        description.set(publication.description)
                    }
                }
            }
        }
    }
}

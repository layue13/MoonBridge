import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import groovy.json.JsonSlurper
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

plugins {
    id("strataproxy.java-library-conventions") apply false
    id("strataproxy.application-conventions") apply false
}

allprojects {
    group = "dev.strataproxy"
    version = "0.1.0-SNAPSHOT"
}

tasks.register("check") {
    group = "verification"
    description = "Runs root-level StrataProxy verification tasks."
}

gradle.projectsEvaluated {
    val releaseName = "strataproxy-${project.version}"
    val releaseStaging = layout.buildDirectory.dir("release/staging/$releaseName")
    val releaseZip = layout.buildDirectory.file("release/$releaseName.zip")
    val releaseChecksum = layout.buildDirectory.file("release/$releaseName.zip.sha256")
    val releaseSbom = layout.buildDirectory.file("release/$releaseName.sbom.cdx.json")
    val releaseMetadata = layout.buildDirectory.file("release/$releaseName.metadata.json")
    val app = project(":proxy-app")
    val admin = project(":proxy-admin-cli")
    val query = project(":proxy-query")
    val appDistZip = app.layout.buildDirectory.file("distributions/strataproxy-${project.version}.zip")
    val adminDistZip = admin.layout.buildDirectory.file("distributions/strataproxy-admin-${project.version}.zip")
    val queryDistZip = query.layout.buildDirectory.file("distributions/strataproxy-query-${project.version}.zip")

    val writeReleaseManifest = tasks.register<ReleaseManifestTask>("writeReleaseManifest") {
        group = "distribution"
        description = "Writes the StrataProxy release manifest."
        dependsOn(
            app.tasks.named("distZip"),
            admin.tasks.named("distZip"),
            query.tasks.named("distZip")
        )
        outputFile.set(layout.buildDirectory.file("release/RELEASE-MANIFEST.txt"))
        nameValue.set(releaseName)
        versionValue.set(project.version.toString())
        appArchive.set(appDistZip.map { it.asFile.name })
        adminArchive.set(adminDistZip.map { it.asFile.name })
        queryArchive.set(queryDistZip.map { it.asFile.name })
    }

    val generateReleaseSbom = tasks.register<GenerateSbomTask>("generateReleaseSbom") {
        group = "distribution"
        description = "Generates a CycloneDX-style SBOM for StrataProxy runtime artifacts."
        outputFile.set(releaseSbom)
        bomVersion.set(project.version.toString())
        components.set(runtimeComponents(app, admin, query))
    }

    val writeReleaseMetadata = tasks.register<ReleaseMetadataTask>("writeReleaseMetadata") {
        group = "distribution"
        description = "Writes release metadata with checksums and optional HMAC signature."
        dependsOn(
            app.tasks.named("distZip"),
            admin.tasks.named("distZip"),
            query.tasks.named("distZip"),
            generateReleaseSbom
        )
        outputFile.set(releaseMetadata)
        releaseNameValue.set(releaseName)
        versionValue.set(project.version.toString())
        signingKey.set(providers.gradleProperty("strataproxy.releaseSigningKey")
            .orElse(providers.environmentVariable("STRATAPROXY_RELEASE_SIGNING_KEY"))
            .orElse(""))
        artifactFiles.from(appDistZip, adminDistZip, queryDistZip, releaseSbom)
    }

    val stageRelease = tasks.register<Sync>("stageRelease") {
        group = "distribution"
        description = "Stages all StrataProxy release artifacts."
        dependsOn(
            app.tasks.named("distZip"),
            admin.tasks.named("distZip"),
            query.tasks.named("distZip"),
            writeReleaseManifest,
            generateReleaseSbom,
            writeReleaseMetadata
        )
        into(releaseStaging)
        from("README.md")
        from("deployment") {
            into("deployment")
        }
        from("proxy-app/src/main/resources/config") {
            into("config")
        }
        from(layout.buildDirectory.file("release/RELEASE-MANIFEST.txt"))
        from(releaseSbom)
        from(releaseMetadata)
        from(appDistZip) {
            into("archives")
        }
        from(adminDistZip) {
            into("archives")
        }
        from(queryDistZip) {
            into("archives")
        }
    }

    val releaseBundle = tasks.register<Zip>("releaseBundle") {
        group = "distribution"
        description = "Builds a distributable StrataProxy release bundle."
        dependsOn(stageRelease)
        archiveFileName.set("$releaseName.zip")
        destinationDirectory.set(layout.buildDirectory.dir("release"))
        from(releaseStaging)
    }

    val releaseChecksums = tasks.register<Sha256FilesTask>("releaseChecksums") {
        group = "distribution"
        description = "Writes SHA-256 checksums for StrataProxy release artifacts."
        dependsOn(releaseBundle)
        inputFiles.from(releaseZip, appDistZip, adminDistZip, queryDistZip, releaseSbom, releaseMetadata)
        outputFile.set(releaseChecksum)
    }

    val releaseAuditSmokeTest = tasks.register<ReleaseAuditTask>("releaseAuditSmokeTest") {
        group = "verification"
        description = "Validates release metadata, SBOM, and checksum artifacts."
        dependsOn(releaseChecksums)
        metadataFile.set(releaseMetadata)
        sbomFile.set(releaseSbom)
        checksumFile.set(releaseChecksum)
    }

    val performanceProfilesSmokeTest = tasks.register<PerformanceProfilesAuditTask>("performanceProfilesSmokeTest") {
        group = "verification"
        description = "Validates packaged StrataProxy performance profile definitions."
        runnerFile.set(layout.projectDirectory.file("deployment/performance/run_profile.py"))
        profileFiles.from(fileTree("deployment/performance") {
            include("profiles/*.json")
            include("profile-result-template.json")
        })
    }

    tasks.register("release") {
        group = "distribution"
        description = "Builds the release bundle and SHA-256 checksum."
        dependsOn(releaseAuditSmokeTest)
    }

    tasks.named("check") {
        dependsOn(releaseAuditSmokeTest)
        dependsOn(performanceProfilesSmokeTest)
    }
}

fun runtimeComponents(vararg projects: Project): List<String> {
    return projects
        .flatMap { project ->
            project.configurations.getByName("runtimeClasspath")
                .resolvedConfiguration
                .resolvedArtifacts
                .map { artifact ->
                    val id = artifact.moduleVersion.id
                    "${id.group}:${id.name}:${id.version}:${artifact.type}:${artifact.file.name}"
                }
        }
        .distinct()
        .sorted()
}

abstract class ReleaseManifestTask : DefaultTask() {
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Input
    abstract val nameValue: Property<String>

    @get:Input
    abstract val versionValue: Property<String>

    @get:Input
    abstract val appArchive: Property<String>

    @get:Input
    abstract val adminArchive: Property<String>

    @get:Input
    abstract val queryArchive: Property<String>

    @TaskAction
    fun writeManifest() {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            name=${nameValue.get()}
            version=${versionValue.get()}
            java=25
            appArchive=${appArchive.get()}
            adminArchive=${adminArchive.get()}
            queryArchive=${queryArchive.get()}
            includes=deployment,configs,observability,checksums
            """.trimIndent() + System.lineSeparator()
        )
    }
}

abstract class Sha256FilesTask : DefaultTask() {
    @get:InputFiles
    abstract val inputFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun writeChecksums() {
        val digest = MessageDigest.getInstance("SHA-256")
        val lines = inputFiles.files
            .sortedBy { it.name }
            .map { archive ->
                digest.reset()
                archive.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        digest.update(buffer, 0, read)
                    }
                }
                val checksum = digest.digest().joinToString("") { "%02x".format(it) }
                "$checksum  ${archivePathLabel(archive, outputFile.get().asFile.parentFile)}"
            }
        val checksumFile = outputFile.get().asFile
        checksumFile.parentFile.mkdirs()
        checksumFile.writeText(lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
    }

    private fun archivePathLabel(archive: File, checksumDirectory: File): String {
        return if (archive.parentFile == checksumDirectory) {
            archive.name
        } else {
            "archives/${archive.name}"
        }
    }
}

abstract class GenerateSbomTask : DefaultTask() {
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Input
    abstract val bomVersion: Property<String>

    @get:Input
    abstract val components: ListProperty<String>

    @TaskAction
    fun generate() {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        val componentJson = components.get().joinToString(",\n") { component ->
            val parts = component.split(':', limit = 5)
            val group = parts.getOrElse(0) { "" }
            val name = parts.getOrElse(1) { "" }
            val version = parts.getOrElse(2) { "" }
            val type = parts.getOrElse(3) { "jar" }
            val fileName = parts.getOrElse(4) { "$name-$version.$type" }
            """
                {
                  "type": "library",
                  "group": "${json(group)}",
                  "name": "${json(name)}",
                  "version": "${json(version)}",
                  "purl": "pkg:maven/${json(group)}/${json(name)}@${json(version)}",
                  "properties": [
                    {"name": "strataproxy:artifactType", "value": "${json(type)}"},
                    {"name": "strataproxy:fileName", "value": "${json(fileName)}"}
                  ]
                }
            """.trimIndent()
        }
        file.writeText(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.5",
              "serialNumber": "urn:uuid:${stableUuid("strataproxy-${bomVersion.get()}-${components.get().joinToString("|")}")}",
              "version": 1,
              "metadata": {
                "component": {
                  "type": "application",
                  "group": "dev.strataproxy",
                  "name": "strataproxy",
                  "version": "${json(bomVersion.get())}"
                }
              },
              "components": [
            $componentJson
              ]
            }
            """.trimIndent() + System.lineSeparator()
        )
    }

    private fun stableUuid(input: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = hash.take(16).joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private fun json(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}

abstract class ReleaseMetadataTask : DefaultTask() {
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Input
    abstract val releaseNameValue: Property<String>

    @get:Input
    abstract val versionValue: Property<String>

    @get:Input
    abstract val signingKey: Property<String>

    @get:InputFiles
    abstract val artifactFiles: ConfigurableFileCollection

    @TaskAction
    fun writeMetadata() {
        val artifacts = artifactFiles.files
            .sortedBy { it.name }
            .map { file ->
                ReleaseArtifact(artifactPathLabel(file, outputFile.get().asFile.parentFile), file.length(), sha256(file))
            }
        val payload = artifacts.joinToString("|") { "${it.name}:${it.size}:${it.sha256}" }
        val key = signingKey.get()
        val signature = if (key.isBlank()) "" else hmacSha256(key, payload)
        val signatureMode = if (key.isBlank()) "unsigned" else "hmac-sha256"
        val artifactJson = artifacts.joinToString(",\n") { artifact ->
            """
                {
                  "name": "${json(artifact.name)}",
                  "size": ${artifact.size},
                  "sha256": "${artifact.sha256}"
                }
            """.trimIndent()
        }
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "name": "${json(releaseNameValue.get())}",
              "version": "${json(versionValue.get())}",
              "java": "25",
              "artifacts": [
            $artifactJson
              ],
              "signature": {
                "mode": "$signatureMode",
                "payload": "${json(payload)}",
                "value": "$signature"
              }
            }
            """.trimIndent() + System.lineSeparator()
        )
    }

    private data class ReleaseArtifact(val name: String, val size: Long, val sha256: String)

    private fun artifactPathLabel(artifact: File, metadataDirectory: File): String {
        return if (artifact.parentFile == metadataDirectory) {
            artifact.name
        } else {
            "archives/${artifact.name}"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun json(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}

abstract class ReleaseAuditTask : DefaultTask() {
    @get:InputFile
    abstract val metadataFile: RegularFileProperty

    @get:InputFile
    abstract val sbomFile: RegularFileProperty

    @get:InputFile
    abstract val checksumFile: RegularFileProperty

    @TaskAction
    fun audit() {
        val metadata = metadataFile.get().asFile.readText()
        val sbom = sbomFile.get().asFile.readText()
        val checksums = checksumFile.get().asFile.readText()
        listOf(
            "\"name\": \"strataproxy-",
            "\"java\": \"25\"",
            "\"signature\":",
            "archives/strataproxy-admin-",
            "archives/strataproxy-query-"
        ).forEach { token ->
            require(metadata.contains(token)) { "release metadata missing $token" }
        }
        listOf(
            "\"bomFormat\": \"CycloneDX\"",
            "\"specVersion\": \"1.5\"",
            "\"components\": [",
            "pkg:maven/io.netty/netty-transport",
            "pkg:maven/com.fasterxml.jackson.core/jackson-databind",
            "pkg:maven/info.picocli/picocli"
        ).forEach { token ->
            require(sbom.contains(token)) { "release SBOM missing $token" }
        }
        listOf(
            "strataproxy-0.1.0-SNAPSHOT.zip",
            "strataproxy-0.1.0-SNAPSHOT.sbom.cdx.json",
            "strataproxy-0.1.0-SNAPSHOT.metadata.json",
            "archives/strataproxy-admin-0.1.0-SNAPSHOT.zip",
            "archives/strataproxy-query-0.1.0-SNAPSHOT.zip"
        ).forEach { token ->
            require(checksums.contains(token)) { "release checksums missing $token" }
        }
    }
}

abstract class PerformanceProfilesAuditTask : DefaultTask() {
    @get:InputFile
    abstract val runnerFile: RegularFileProperty

    @get:InputFiles
    abstract val profileFiles: ConfigurableFileCollection

    @TaskAction
    fun audit() {
        val runner = runnerFile.get().asFile
        require(runner.isFile) { "missing performance profile runner: ${runner.path}" }
        val runnerText = runner.readText()
        require(runnerText.contains("evaluate_gates")) { "performance profile runner must evaluate gates" }
        require(runnerText.contains("/native-capabilities")) { "performance profile runner must capture native capabilities" }
        val files = profileFiles.files.sortedBy { it.name }
        require(files.size >= 4) { "expected performance profiles and result template" }
        val parser = JsonSlurper()
        val profileIds = mutableSetOf<String>()
        for (file in files) {
            val parsed = parser.parse(file) as Map<*, *>
            if (file.name == "profile-result-template.json") {
                require(parsed.containsKey("gateResults")) { "profile result template missing gateResults" }
                require(parsed.containsKey("observations")) { "profile result template missing observations" }
                val observations = parsed["observations"] as? Map<*, *>
                require(observations?.containsKey("nativeRuntimeJson") == true) { "profile result template missing nativeRuntimeJson" }
                continue
            }
            val id = parsed["id"] as? String
            require(!id.isNullOrBlank()) { "profile ${file.name} missing id" }
            require(profileIds.add(id)) { "duplicate performance profile id: $id" }
            require(parsed["java"] == "25") { "profile $id must target Java 25" }
            require(parsed.containsKey("commands")) { "profile $id missing commands" }
            require(parsed.containsKey("gates")) { "profile $id missing gates" }
            val gates = parsed["gates"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val commands = parsed["commands"] as? List<*> ?: emptyList<Any>()
            if (gates.containsKey("maxP99ProxyForwardingLatencyMillis")) {
                require(commands.any { command ->
                    val commandMap = command as? Map<*, *> ?: return@any false
                    val args = commandMap["args"] as? List<*> ?: return@any false
                    args.contains("--measure-echo-latency")
                }) { "profile $id has p99 latency gate but no --measure-echo-latency command" }
            }
        }
        require(profileIds.contains("smoke")) { "missing smoke performance profile" }
        require(profileIds.contains("acceptance-linux-native-java25")) { "missing Linux native Java 25 acceptance profile" }
        require(profileIds.contains("compression-rewrite-linux-native-java25")) { "missing compression rewrite performance profile" }
    }
}

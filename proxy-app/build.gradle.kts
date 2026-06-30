import groovy.json.JsonSlurper
import org.gradle.api.tasks.Exec

plugins {
    id("strataproxy.application-conventions")
}

dependencies {
    implementation(project(":proxy-bootstrap"))
    implementation(project(":proxy-network"))
    implementation(project(":proxy-registry"))
    implementation(project(":proxy-routing"))
    implementation(project(":proxy-compression"))
    implementation(project(":proxy-observability"))
    implementation(project(":proxy-packet-analysis"))
    implementation(project(":proxy-admin-api"))
    implementation(project(":proxy-codec-minecraft"))
    implementation(project(":proxy-native"))
}

val installedDistSmokeTest = tasks.register<Exec>("installedDistSmokeTest") {
    group = "verification"
    description = "Runs the installed StrataProxy distribution in --validate-config mode."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/strataproxy")
    inputs.dir(installDir)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "strataproxy.bat" else "strataproxy").asFile
        val config = installDir.get().file("config/strataproxy.yml").asFile
        commandLine(script.absolutePath, "--validate-config", config.absolutePath)
    }
}

val installedDistProductionConfigSmokeTest = tasks.register<Exec>("installedDistProductionConfigSmokeTest") {
    group = "verification"
    description = "Validates the installed production StrataProxy config."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/strataproxy")
    inputs.dir(installDir)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "strataproxy.bat" else "strataproxy").asFile
        val config = installDir.get().file("config/strataproxy-production.yml").asFile
        environment("STRATAPROXY_ADMIN_TOKEN", "smoke-token")
        commandLine(script.absolutePath, "--validate-config", config.absolutePath)
    }
}

val deploymentAssetsSmokeTest = tasks.register("deploymentAssetsSmokeTest") {
    group = "verification"
    description = "Validates packaged deployment observability assets."

    val dashboard = layout.projectDirectory.file("../deployment/observability/grafana/strataproxy-overview.json")
    val alerts = layout.projectDirectory.file("../deployment/observability/prometheus/strataproxy-alerts.yml")
    inputs.file(dashboard)
    inputs.file(alerts)

    doLast {
        val parsed = JsonSlurper().parse(dashboard.asFile) as Map<*, *>
        require(parsed["uid"] == "strataproxy-overview") { "Grafana dashboard uid mismatch" }
        val panels = parsed["panels"] as List<*>
        require(panels.size >= 8) { "Grafana dashboard must contain operational panels" }
        val dashboardText = dashboard.asFile.readText()
        listOf(
            "strataproxy_connections_active",
            "strataproxy_event_loop_delay_seconds",
            "strataproxy_pooled_direct_memory_bytes",
            "strataproxy_compression_saved_bytes_total",
            "strataproxy_packet_anomalies_total",
            "strataproxy_relay_backpressure_events_total"
        ).forEach { metric ->
            require(dashboardText.contains(metric)) { "Grafana dashboard missing $metric" }
        }

        val alertText = alerts.asFile.readText()
        listOf(
            "StrataProxyTargetDown",
            "StrataProxyNoReadyBackends",
            "StrataProxyEventLoopDelayHigh",
            "StrataProxyHeapPressure",
            "StrataProxyPacketAnomalies",
            "strataproxy_routes_failed_total",
            "strataproxy_backend_connect_failures_total"
        ).forEach { token ->
            require(alertText.contains(token)) { "Prometheus alerts missing $token" }
        }
    }
}

val installedDistHelpSmokeTest = tasks.register<Exec>("installedDistHelpSmokeTest") {
    group = "verification"
    description = "Runs the installed StrataProxy distribution help command."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/strataproxy")
    inputs.dir(installDir)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "strataproxy.bat" else "strataproxy").asFile
        commandLine(script.absolutePath, "--help")
    }
}

val installedDistVersionSmokeTest = tasks.register<Exec>("installedDistVersionSmokeTest") {
    group = "verification"
    description = "Runs the installed StrataProxy distribution version command."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/strataproxy")
    inputs.dir(installDir)

    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val script = installDir.get().file("bin/" + if (windows) "strataproxy.bat" else "strataproxy").asFile
        commandLine(script.absolutePath, "--version")
    }
}

tasks.named("check") {
    dependsOn(installedDistSmokeTest)
    dependsOn(installedDistProductionConfigSmokeTest)
    dependsOn(deploymentAssetsSmokeTest)
    dependsOn(installedDistHelpSmokeTest)
    dependsOn(installedDistVersionSmokeTest)
}

application {
    mainClass.set("dev.strataproxy.app.StrataProxyLauncher")
    applicationName = "strataproxy"
}

distributions {
    main {
        contents {
            from("src/main/resources/config") {
                into("config")
            }
            from("../deployment") {
                into("deployment")
            }
        }
    }
}

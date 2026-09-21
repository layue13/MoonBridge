package dev.strataproxy.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class ConfigPathResolver {
    private static final Path DEFAULT_CONFIG = Path.of("config", "strataproxy.yml");

    private ConfigPathResolver() {
    }

    static Path resolve(String[] args) {
        return resolve(args, System.getenv(), Path.of("").toAbsolutePath(), detectedAppHome());
    }

    static Path resolve(String[] args, Map<String, String> environment, Path workingDirectory) {
        return resolve(args, environment, workingDirectory, null);
    }

    static Path resolve(String[] args, Map<String, String> environment, Path workingDirectory, Path detectedAppHome) {
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            return Path.of(args[0]);
        }

        var workingConfig = workingDirectory.resolve(DEFAULT_CONFIG).normalize();
        if (Files.exists(workingConfig)) {
            return workingConfig;
        }

        var appHome = environment.get("APP_HOME");
        if (appHome != null && !appHome.isBlank()) {
            var appHomeConfig = Path.of(appHome).resolve(DEFAULT_CONFIG).normalize();
            if (Files.exists(appHomeConfig)) {
                return appHomeConfig;
            }
        }

        if (detectedAppHome != null) {
            var appHomeConfig = detectedAppHome.resolve(DEFAULT_CONFIG).normalize();
            if (Files.exists(appHomeConfig)) {
                return appHomeConfig;
            }
        }

        return DEFAULT_CONFIG;
    }

    private static Path detectedAppHome() {
        try {
            var location = ConfigPathResolver.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            var path = Path.of(location).toAbsolutePath();
            var parent = Files.isRegularFile(path) ? path.getParent() : path;
            if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("lib")) {
                return parent.getParent();
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}

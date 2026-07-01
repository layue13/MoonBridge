package dev.strataproxy.bootstrap;

import java.util.List;

/**
 * Result of validating proxy configuration.
 *
 * @param errors blocking validation failures
 * @param warnings non-blocking operational risks or suspicious settings
 */
public record ConfigValidationResult(List<String> errors, List<String> warnings) {
    public ConfigValidationResult {
        errors = List.copyOf(errors == null ? List.of() : errors);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    /**
     * @return {@code true} when no blocking validation errors were found
     */
    public boolean valid() {
        return errors.isEmpty();
    }
}

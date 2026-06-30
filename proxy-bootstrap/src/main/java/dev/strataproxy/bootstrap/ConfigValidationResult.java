package dev.strataproxy.bootstrap;

import java.util.List;

public record ConfigValidationResult(List<String> errors, List<String> warnings) {
    public ConfigValidationResult {
        errors = List.copyOf(errors == null ? List.of() : errors);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public boolean valid() {
        return errors.isEmpty();
    }
}

package dev.strataproxy.api;

import java.util.Objects;

/** Shared validation for the plain String convenience overloads. */
final class PlainTextValidation {
    private PlainTextValidation() { }

    static void validateString(String text, boolean allowEmpty, String name) {
        Objects.requireNonNull(text, name);
        int length = text.codePointCount(0, text.length());
        if ((!allowEmpty && text.isBlank()) || length > 1024) {
            throw new IllegalArgumentException(name + (allowEmpty
                    ? " must contain at most 1024 Unicode code points"
                    : " must contain 1 to 1024 Unicode code points"));
        }
    }
}

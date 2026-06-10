package org.example.model;

import java.util.Locale;
import java.util.Objects;

public record PersonName(String first, String last, String display, String normalized) {
    private static final String LEGACY_WHITESPACE_REGEX = "[ \\t\\n\\u000B\\f\\r]+";

    public PersonName {
        first = normalizeDisplayPart(first);
        last = normalizeDisplayPart(last);
        display = normalizeDisplayPart(display);
        if (display.isEmpty()) {
            throw new IllegalArgumentException("Person name must not be blank");
        }
        normalized = display.toLowerCase(Locale.ROOT);
    }

    public static PersonName of(String display) {
        String normalizedDisplay = normalizeDisplayPart(display);
        int lastSpace = normalizedDisplay.lastIndexOf(' ');
        if (lastSpace < 0) {
            return new PersonName(normalizedDisplay, "", normalizedDisplay, "");
        }
        String first = normalizedDisplay.substring(0, lastSpace);
        String last = normalizedDisplay.substring(lastSpace + 1);
        return new PersonName(first, last, normalizedDisplay, "");
    }

    public static PersonName ofParts(String first, String last) {
        String normalizedFirst = normalizeDisplayPart(first);
        String normalizedLast = normalizeDisplayPart(last);
        String display = (normalizedFirst + " " + normalizedLast).trim();
        return new PersonName(normalizedFirst, normalizedLast, display, "");
    }

    public boolean isFull() {
        return !first.isBlank() && !last.isBlank();
    }

    @Override
    public boolean equals(Object other) { return other instanceof PersonName that && normalized().equals(that.normalized()); }

    @Override
    public int hashCode() { return normalized().hashCode(); }

    private static String normalizeDisplayPart(String value) {
        return Objects.requireNonNull(value, "value").trim().replaceAll(LEGACY_WHITESPACE_REGEX, " ");
    }
}

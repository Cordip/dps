package org.example.model;

import java.util.Locale;

public enum Gender {
    MALE, FEMALE, UNKNOWN;

    public static Gender parse(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return UNKNOWN;
        }
        return switch (rawValue.trim().toLowerCase(Locale.ROOT)) {
            case "male", "m" -> MALE;
            case "female", "f" -> FEMALE;
            default -> UNKNOWN;
        };
    }
}

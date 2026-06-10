package org.example.model;

import java.util.Objects;

public record PersonId(String value) {
    public PersonId {
        Objects.requireNonNull(value, "value");
        if (!isCanonical(value)) throw new IllegalArgumentException("Invalid person id: " + value);
    }

    public static PersonId parse(String value) { return new PersonId(value); }

    public static boolean isCanonical(String value) {
        return value != null && value.matches("P0|P[1-9][0-9]*");
    }
}

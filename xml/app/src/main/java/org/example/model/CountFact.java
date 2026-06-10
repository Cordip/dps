package org.example.model;

import java.util.Objects;

public record CountFact(RelationRole role, int value) {
    public CountFact {
        Objects.requireNonNull(role, "role");
        if (value < 0) throw new IllegalArgumentException("Count value must not be negative");
    }
}

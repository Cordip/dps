package org.example.model;

public enum RelationSubtype {
    GENERIC, WIFE, HUSBAND, FATHER, MOTHER, BROTHER, SISTER, SON, DAUGHTER;

    public Gender ownerGenderRequirement() {
        return switch (this) {
            case WIFE -> Gender.MALE;
            case HUSBAND -> Gender.FEMALE;
            default -> Gender.UNKNOWN;
        };
    }

    public Gender targetGenderRequirement() {
        return switch (this) {
            case WIFE, MOTHER, SISTER, DAUGHTER -> Gender.FEMALE;
            case HUSBAND, FATHER, BROTHER, SON -> Gender.MALE;
            case GENERIC -> Gender.UNKNOWN;
        };
    }

    public boolean reverseCompatible(RelationSubtype reverseSubtype) {
        if (this == GENERIC || reverseSubtype == GENERIC) {
            return true;
        }
        return switch (this) {
            case WIFE -> reverseSubtype == HUSBAND;
            case HUSBAND -> reverseSubtype == WIFE;
            case FATHER, MOTHER -> reverseSubtype == SON || reverseSubtype == DAUGHTER;
            case SON, DAUGHTER -> reverseSubtype == FATHER || reverseSubtype == MOTHER;
            case BROTHER, SISTER -> reverseSubtype == BROTHER || reverseSubtype == SISTER;
            case GENERIC -> true;
        };
    }
}

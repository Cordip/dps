package org.example.model;

public enum RelationRole {
    SPOUSE, PARENT, SIBLING, CHILD;

    public RelationRole reciprocal() {
        return switch (this) {
            case PARENT -> CHILD;
            case CHILD -> PARENT;
            case SPOUSE, SIBLING -> this;
        };
    }
}

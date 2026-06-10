package org.example.model;

import java.util.Objects;

public record RelationMention(RelationRole role, RelationSubtype subtype, PersonId targetId, PersonName targetName) {
    public RelationMention {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(subtype, "subtype");
        if ((targetId == null) == (targetName == null)) throw new IllegalArgumentException("Relation mention must have exactly one target");
    }

    public static RelationMention byId(RelationRole role, RelationSubtype subtype, PersonId targetId) { return new RelationMention(role, subtype, targetId, null); }

    public static RelationMention byName(RelationRole role, RelationSubtype subtype, PersonName targetName) { return new RelationMention(role, subtype, null, targetName); }
}

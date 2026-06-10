package org.example.structured;

import org.example.model.RelationRole;
import org.example.model.RelationSubtype;

public final class StructuredDaughterRef extends StructuredRelationRef {
    public StructuredDaughterRef() {
    }

    public StructuredDaughterRef(StructuredPerson target, String provenance) {
        super(target, provenance);
    }

    @Override
    public RelationRole role() {
        return RelationRole.CHILD;
    }

    @Override
    public RelationSubtype subtype() {
        return RelationSubtype.DAUGHTER;
    }
}

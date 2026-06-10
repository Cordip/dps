package org.example.structured;

import org.example.model.RelationRole;
import org.example.model.RelationSubtype;

public final class StructuredBrotherRef extends StructuredRelationRef {
    public StructuredBrotherRef() {
    }

    public StructuredBrotherRef(StructuredPerson target, String provenance) {
        super(target, provenance);
    }

    @Override
    public RelationRole role() {
        return RelationRole.SIBLING;
    }

    @Override
    public RelationSubtype subtype() {
        return RelationSubtype.BROTHER;
    }
}

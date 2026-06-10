package org.example.structured;

import org.example.model.RelationRole;
import org.example.model.RelationSubtype;

public final class StructuredSiblingRef extends StructuredRelationRef {
    public StructuredSiblingRef() {
    }

    public StructuredSiblingRef(StructuredPerson target, String provenance) {
        super(target, provenance);
    }

    @Override
    public RelationRole role() {
        return RelationRole.SIBLING;
    }

    @Override
    public RelationSubtype subtype() {
        return RelationSubtype.GENERIC;
    }
}

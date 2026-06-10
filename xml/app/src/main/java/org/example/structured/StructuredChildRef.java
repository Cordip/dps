package org.example.structured;

import org.example.model.RelationRole;
import org.example.model.RelationSubtype;

public final class StructuredChildRef extends StructuredRelationRef {
    public StructuredChildRef() {
    }

    public StructuredChildRef(StructuredPerson target, String provenance) {
        super(target, provenance);
    }

    @Override
    public RelationRole role() {
        return RelationRole.CHILD;
    }

    @Override
    public RelationSubtype subtype() {
        return RelationSubtype.GENERIC;
    }
}

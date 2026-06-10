package org.example.structured;

import org.example.model.RelationRole;
import org.example.model.RelationSubtype;

public final class StructuredParentRef extends StructuredRelationRef {
    public StructuredParentRef() {
    }

    public StructuredParentRef(StructuredPerson target, String provenance) {
        super(target, provenance);
    }

    @Override
    public RelationRole role() {
        return RelationRole.PARENT;
    }

    @Override
    public RelationSubtype subtype() {
        return RelationSubtype.GENERIC;
    }
}

package org.example.structured;

import org.example.model.RelationRole;
import org.example.model.RelationSubtype;

public final class StructuredSpouseRef extends StructuredRelationRef {
    public StructuredSpouseRef() {
    }

    public StructuredSpouseRef(StructuredPerson target, String provenance) {
        super(target, provenance);
    }

    @Override
    public RelationRole role() {
        return RelationRole.SPOUSE;
    }

    @Override
    public RelationSubtype subtype() {
        return RelationSubtype.GENERIC;
    }
}

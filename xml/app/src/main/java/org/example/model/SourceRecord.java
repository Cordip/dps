package org.example.model;

import java.util.List;

public record SourceRecord(int recordNumber, List<PersonId> ownerIds, List<String> invalidOwnerIdTokens,
                           PersonName name, Gender gender, List<RelationMention> relations, List<CountFact> counts) {
    public SourceRecord {
        ownerIds = List.copyOf(ownerIds);
        invalidOwnerIdTokens = List.copyOf(invalidOwnerIdTokens);
        gender = gender == null ? Gender.UNKNOWN : gender;
        relations = List.copyOf(relations);
        counts = List.copyOf(counts);
    }
}

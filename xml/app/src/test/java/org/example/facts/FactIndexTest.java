package org.example.facts;

import org.example.model.Gender;
import org.example.model.PersonId;
import org.example.model.PersonName;
import org.example.model.SourceRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class FactIndexTest {
    @Test
    void recordsWithInvalidOrMultipleOwnerIdsAreDiscardedAtIndexBoundary() {
        SourceRecord invalidOwnerId = new SourceRecord(
            1,
            List.of(),
            List.of("BAD"),
            PersonName.of("Invalid Owner"),
            Gender.UNKNOWN,
            List.of(),
            List.of()
        );
        SourceRecord multipleOwnerIds = new SourceRecord(
            2,
            List.of(PersonId.parse("P1"), PersonId.parse("P2")),
            List.of(),
            PersonName.of("Multiple Owners"),
            Gender.UNKNOWN,
            List.of(),
            List.of()
        );
        SourceRecord validOwner = new SourceRecord(
            3,
            List.of(PersonId.parse("P3")),
            List.of(),
            PersonName.of("Valid Owner"),
            Gender.UNKNOWN,
            List.of(),
            List.of()
        );

        FactIndex facts = FactIndex.from(List.of(invalidOwnerId, multipleOwnerIds, validOwner));

        assertEquals(Set.of(PersonId.parse("P3")), facts.knownPersonIds());
        assertFalse(facts.hasPendingRecords());
    }
}

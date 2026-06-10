package org.example.structured;

import org.example.facts.FactIndex;
import org.example.model.CountFact;
import org.example.model.Gender;
import org.example.model.PersonId;
import org.example.model.PersonName;
import org.example.model.RelationMention;
import org.example.model.RelationRole;
import org.example.model.RelationSubtype;
import org.example.model.SourceRecord;
import org.example.relation.ResolvedRelation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class StructuredPeopleAssemblerTest {
    @Test
    void omitsPartialNameWhenNoFullNameCanBeFormed() {
        StructuredPeopleDocument document = assemble(
            List.of(record(1, "P1", PersonName.of("Single"), Gender.UNKNOWN, List.of(), List.of())),
            List.of()
        );

        assertNull(document.people().getFirst().name());
    }

    @Test
    void createsObjectIdRefsForRelations() {
        StructuredPeopleDocument document = assemble(
            List.of(
                record(1, "P1", PersonName.of("Jane Parent"), Gender.FEMALE, List.of(), List.of()),
                record(2, "P2", PersonName.of("John Child"), Gender.MALE, List.of(), List.of())
            ),
            List.of(resolved("P1", RelationRole.CHILD, RelationSubtype.GENERIC, "P2"))
        );

        StructuredPerson parent = person(document, "P1");
        StructuredPerson child = person(document, "P2");
        StructuredRelationRef childRef = parent.relations().children().values().getFirst();

        assertSame(child, childRef.target());
        assertEquals("P2", childRef.targetId());
        assertInstanceOf(StructuredSonRef.class, childRef);
        assertEquals("derived-subtype", childRef.provenance());
    }

    @Test
    void preservesCountValidationValues() {
        StructuredPeopleDocument document = assemble(
            List.of(
                record(
                    1,
                    "P1",
                    PersonName.of("Jane Parent"),
                    Gender.FEMALE,
                    List.of(),
                    List.of(new CountFact(RelationRole.CHILD, 2))
                ),
                record(2, "P2", PersonName.of("John Child"), Gender.MALE, List.of(), List.of())
            ),
            List.of(resolved("P1", RelationRole.CHILD, RelationSubtype.GENERIC, "P2"))
        );

        StructuredCount children = person(document, "P1").counts().children();

        assertEquals(2, children.source());
        assertEquals(1, children.inferred());
        assertEquals("under-inferred", children.validation());
    }

    @Test
    void createsReciprocalRelationsWithDerivedProvenance() {
        StructuredPeopleDocument document = assemble(
            List.of(
                record(1, "P1", PersonName.of("Jane Parent"), Gender.FEMALE, List.of(), List.of()),
                record(2, "P2", PersonName.of("John Child"), Gender.MALE, List.of(), List.of())
            ),
            List.of(resolved("P1", RelationRole.CHILD, RelationSubtype.GENERIC, "P2"))
        );

        StructuredRelationRef reciprocal = person(document, "P2").relations().parents().values().getFirst();

        assertInstanceOf(StructuredMotherRef.class, reciprocal);
        assertEquals("P1", reciprocal.targetId());
        assertEquals("derived-reciprocal", reciprocal.provenance());
    }

    @Test
    void sortsRelationsByRoleAndTargetId() {
        StructuredPeopleDocument document = assemble(
            List.of(
                record(1, "P1", PersonName.of("Jane Parent"), Gender.FEMALE, List.of(), List.of()),
                record(2, "P2", PersonName.of("Child Two"), Gender.MALE, List.of(), List.of()),
                record(3, "P3", PersonName.of("Child Three"), Gender.MALE, List.of(), List.of())
            ),
            List.of(
                resolved("P1", RelationRole.CHILD, RelationSubtype.GENERIC, "P3"),
                resolved("P1", RelationRole.CHILD, RelationSubtype.GENERIC, "P2")
            )
        );

        List<StructuredRelationRef> children = person(document, "P1").relations().children().values();

        assertEquals(List.of("P2", "P3"), children.stream().map(StructuredRelationRef::targetId).toList());
    }

    private StructuredPeopleDocument assemble(List<SourceRecord> records, List<ResolvedRelation> relations) {
        return new StructuredPeopleAssembler().assemble(FactIndex.from(records), relations);
    }

    private ResolvedRelation resolved(String ownerId, RelationRole role, RelationSubtype subtype, String targetId) {
        return new ResolvedRelation(
            PersonId.parse(ownerId),
            RelationMention.byId(role, subtype, PersonId.parse(targetId)),
            PersonId.parse(targetId)
        );
    }

    private SourceRecord record(
        int recordNumber,
        String ownerId,
        PersonName name,
        Gender gender,
        List<RelationMention> relations,
        List<CountFact> counts
    ) {
        return new SourceRecord(
            recordNumber,
            List.of(PersonId.parse(ownerId)),
            List.of(),
            name,
            gender,
            relations,
            counts
        );
    }

    private StructuredPerson person(StructuredPeopleDocument document, String id) {
        return document.people().stream()
            .filter(person -> person.id().equals(id))
            .findFirst()
            .orElseThrow();
    }
}

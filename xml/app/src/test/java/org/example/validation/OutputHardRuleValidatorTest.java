package org.example.validation;

import org.example.model.Gender;
import org.example.model.RelationRole;
import org.example.model.RelationSubtype;
import org.example.structured.StructuredChildRef;
import org.example.structured.StructuredChildren;
import org.example.structured.StructuredCount;
import org.example.structured.StructuredCounts;
import org.example.structured.StructuredDaughterRef;
import org.example.structured.StructuredFatherRef;
import org.example.structured.StructuredGender;
import org.example.structured.StructuredHusbandRef;
import org.example.structured.StructuredMotherRef;
import org.example.structured.StructuredName;
import org.example.structured.StructuredParentRef;
import org.example.structured.StructuredParents;
import org.example.structured.StructuredPeopleDocument;
import org.example.structured.StructuredPerson;
import org.example.structured.StructuredRelationRef;
import org.example.structured.StructuredRelations;
import org.example.structured.StructuredSiblingRef;
import org.example.structured.StructuredSiblings;
import org.example.structured.StructuredSonRef;
import org.example.structured.StructuredSpouseRef;
import org.example.structured.StructuredSpouses;
import org.example.structured.StructuredWifeRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OutputHardRuleValidatorTest {
    private final OutputHardRuleValidator validator = new OutputHardRuleValidator();

    @Test
    void acceptsSimpleStrictDocument() {
        StructuredPerson p1 = person("P1");
        StructuredPerson p2 = person("P2");
        add(p1, relation(RelationRole.SPOUSE, RelationSubtype.GENERIC, p2));

        assertTrue(validator.isValid(document(p1, p2)));
    }

    @Test
    void rejectsDuplicateIds() {
        assertFalse(validator.isValid(document(person("P1"), person("P1"))));
    }

    @Test
    void rejectsP0() {
        assertFalse(validator.isValid(document(person("P0"))));
    }

    @Test
    void rejectsRootCountMismatch() {
        StructuredPeopleDocument document = new StructuredPeopleDocument();
        document.people().add(person("P1"));

        assertFalse(validator.isValid(document));
    }

    @Test
    void rejectsPartialName() {
        assertFalse(validator.isValid(document(person("P1", new StructuredName("", "One"), Gender.UNKNOWN))));
        assertFalse(validator.isValid(document(person("P2", new StructuredName("  ", "Two"), Gender.UNKNOWN))));
    }

    @Test
    void rejectsNegativeCountValues() {
        assertFalse(validator.isValid(document(person("P1", Gender.UNKNOWN, List.of(),
            new StructuredCounts(
                new StructuredCount(-1, 0, "match"),
                StructuredCount.unknown(0),
                StructuredCount.unknown(0),
                StructuredCount.unknown(0)
            )))));
        assertFalse(validator.isValid(document(person("P2", Gender.UNKNOWN, List.of(),
            new StructuredCounts(
                new StructuredCount(null, -1, "source-unknown"),
                StructuredCount.unknown(0),
                StructuredCount.unknown(0),
                StructuredCount.unknown(0)
            )))));
    }

    @Test
    void rejectsAmbiguousSourceCount() {
        assertFalse(validator.isValid(document(person("P1", Gender.UNKNOWN, List.of(),
            new StructuredCounts(
                StructuredCount.unknown(0),
                StructuredCount.unknown(0),
                StructuredCount.unknown(0),
                new StructuredCount(null, 0, "ambiguous-source")
            )))));
    }

    @Test
    void rejectsMissingRelationTarget() {
        StructuredPerson p1 = person("P1");
        add(p1, relation(RelationRole.CHILD, RelationSubtype.GENERIC, person("P99")));

        assertFalse(validator.isValid(document(p1)));
    }

    @Test
    void rejectsSelfRelation() {
        StructuredPerson p1 = person("P1");
        add(p1, relation(RelationRole.SPOUSE, RelationSubtype.GENERIC, p1));

        assertFalse(validator.isValid(document(p1)));
    }

    @Test
    void rejectsDuplicateRoleTarget() {
        StructuredPerson p1 = person("P1");
        StructuredPerson p2 = person("P2");
        add(p1, relation(RelationRole.SPOUSE, RelationSubtype.GENERIC, p2));
        add(p1, relation(RelationRole.SPOUSE, RelationSubtype.GENERIC, p2));

        assertFalse(validator.isValid(document(p1, p2)));
    }

    @Test
    void rejectsConflictingRolesForSamePair() {
        StructuredPerson p1 = person("P1");
        StructuredPerson p2 = person("P2");
        add(p1, relation(RelationRole.SPOUSE, RelationSubtype.GENERIC, p2));
        add(p1, relation(RelationRole.PARENT, RelationSubtype.GENERIC, p2));

        assertFalse(validator.isValid(document(p1, p2)));
    }

    @Test
    void rejectsGenderSubtypeMismatch() {
        StructuredPerson p1 = person("P1", Gender.UNKNOWN);
        StructuredPerson p2 = person("P2", Gender.MALE);
        add(p1, relation(RelationRole.CHILD, RelationSubtype.DAUGHTER, p2));

        assertFalse(validator.isValid(document(p1, p2)));
    }

    @Test
    void rejectsGenericSubtypeWhenTargetGenderKnown() {
        StructuredPerson p1 = person("P1", Gender.UNKNOWN);
        StructuredPerson p2 = person("P2", Gender.MALE);
        add(p1, relation(RelationRole.SIBLING, RelationSubtype.GENERIC, p2));

        assertFalse(validator.isValid(document(p1, p2)));
    }

    @Test
    void rejectsParentCycle() {
        StructuredPerson p1 = person("P1");
        StructuredPerson p2 = person("P2");
        add(p1, relation(RelationRole.PARENT, RelationSubtype.GENERIC, p2));
        add(p2, relation(RelationRole.PARENT, RelationSubtype.GENERIC, p1));

        assertFalse(validator.isValid(document(p1, p2)));
    }

    @Test
    void rejectsParentAndSiblingGraphFailures() {
        StructuredPerson p1 = person("P1");
        StructuredPerson p2 = person("P2");
        StructuredPerson p3 = person("P3");
        StructuredPerson p4 = person("P4");
        StructuredPerson p5 = person("P5");
        StructuredPerson p6 = person("P6");
        add(p1, relation(RelationRole.PARENT, RelationSubtype.GENERIC, p2));
        add(p1, relation(RelationRole.PARENT, RelationSubtype.GENERIC, p3));
        add(p1, relation(RelationRole.PARENT, RelationSubtype.GENERIC, p4));
        add(p2, relation(RelationRole.SIBLING, RelationSubtype.GENERIC, p3));
        add(p3, relation(RelationRole.PARENT, RelationSubtype.GENERIC, p5));
        add(p3, relation(RelationRole.PARENT, RelationSubtype.GENERIC, p6));

        assertFalse(validator.isValid(document(p1, p2, p3, p4, p5, p6)));
    }

    @Test
    void rejectsSourceAndSpouseChildCountFailures() {
        StructuredPerson p1 = person("P1", Gender.UNKNOWN, List.of(), counts(null, null, null, new StructuredCount(1, 0, "under-inferred")));
        StructuredPerson p2 = person("P2", Gender.UNKNOWN, List.of(), counts(null, null, null, new StructuredCount(2, 0, "under-inferred")));
        add(p1, relation(RelationRole.SPOUSE, RelationSubtype.GENERIC, p2));

        StructuredPerson p10 = person("P10", Gender.UNKNOWN, List.of(), counts(null, null, null, new StructuredCount(1, 1, "match")));
        StructuredPerson p20 = person("P20", Gender.UNKNOWN, List.of(), counts(null, null, null, new StructuredCount(1, 1, "match")));
        StructuredPerson p30 = person("P30");
        StructuredPerson p40 = person("P40");
        add(p10, relation(RelationRole.SPOUSE, RelationSubtype.GENERIC, p20));
        add(p10, relation(RelationRole.CHILD, RelationSubtype.GENERIC, p30));
        add(p20, relation(RelationRole.CHILD, RelationSubtype.GENERIC, p40));

        assertFalse(validator.isValid(document(p1, p2)));
        assertFalse(validator.isValid(document(p10, p20, p30, p40)));
    }

    @Test
    void rejectsCoparentPairThatWouldGiveParentDifferentSpouse() {
        StructuredPerson p1 = person("P1");
        StructuredPerson p2 = person("P2");
        StructuredPerson p3 = person("P3");
        StructuredPerson p4 = person("P4");
        add(p1, relation(RelationRole.PARENT, RelationSubtype.GENERIC, p2));
        add(p1, relation(RelationRole.PARENT, RelationSubtype.GENERIC, p3));
        add(p2, relation(RelationRole.SPOUSE, RelationSubtype.GENERIC, p4));

        assertFalse(validator.isValid(document(p1, p2, p3, p4)));
    }

    private StructuredPeopleDocument document(StructuredPerson... people) {
        return StructuredPeopleDocument.of(List.of(people));
    }

    private StructuredPerson person(String id) {
        return person(id, Gender.UNKNOWN);
    }

    private StructuredPerson person(String id, Gender gender) {
        return person(id, null, gender);
    }

    private StructuredPerson person(String id, StructuredName name, Gender gender) {
        return person(id, name, gender, List.of(), StructuredCounts.empty());
    }

    private StructuredPerson person(
        String id,
        Gender gender,
        List<StructuredRelationRef> relations,
        StructuredCounts counts
    ) {
        return person(id, null, gender, relations, counts);
    }

    private StructuredPerson person(
        String id,
        StructuredName name,
        Gender gender,
        List<StructuredRelationRef> relationRefs,
        StructuredCounts counts
    ) {
        StructuredPerson person = new StructuredPerson(
            id,
            name,
            gender(gender),
            counts,
            new StructuredRelations(new StructuredSpouses(), new StructuredParents(), new StructuredSiblings(), new StructuredChildren())
        );
        relationRefs.forEach(ref -> add(person, ref));
        return person;
    }

    private StructuredGender gender(Gender gender) {
        return switch (gender) {
            case MALE -> new StructuredGender("male");
            case FEMALE -> new StructuredGender("female");
            case UNKNOWN -> null;
        };
    }

    private StructuredCounts counts(
        StructuredCount spouses,
        StructuredCount parents,
        StructuredCount siblings,
        StructuredCount children
    ) {
        return new StructuredCounts(
            spouses == null ? StructuredCount.unknown(0) : spouses,
            parents == null ? StructuredCount.unknown(0) : parents,
            siblings == null ? StructuredCount.unknown(0) : siblings,
            children == null ? StructuredCount.unknown(0) : children
        );
    }

    private StructuredRelationRef relation(RelationRole role, RelationSubtype subtype, StructuredPerson target) {
        return switch (role) {
            case SPOUSE -> switch (subtype) {
                case WIFE -> new StructuredWifeRef(target, "source");
                case HUSBAND -> new StructuredHusbandRef(target, "source");
                default -> new StructuredSpouseRef(target, "source");
            };
            case PARENT -> switch (subtype) {
                case FATHER -> new StructuredFatherRef(target, "source");
                case MOTHER -> new StructuredMotherRef(target, "source");
                default -> new StructuredParentRef(target, "source");
            };
            case SIBLING -> new StructuredSiblingRef(target, "source");
            case CHILD -> switch (subtype) {
                case SON -> new StructuredSonRef(target, "source");
                case DAUGHTER -> new StructuredDaughterRef(target, "source");
                default -> new StructuredChildRef(target, "source");
            };
        };
    }

    private void add(StructuredPerson owner, StructuredRelationRef relation) {
        switch (relation.role()) {
            case SPOUSE -> owner.relations().spouses().values().add(relation);
            case PARENT -> owner.relations().parents().values().add(relation);
            case SIBLING -> owner.relations().siblings().values().add(relation);
            case CHILD -> owner.relations().children().values().add(relation);
        }
    }
}

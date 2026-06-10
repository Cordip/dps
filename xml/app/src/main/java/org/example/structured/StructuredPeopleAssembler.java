package org.example.structured;

import org.example.facts.FactIndex;
import org.example.facts.FactIndex.OwnerFacts;
import org.example.model.CountFact;
import org.example.model.Gender;
import org.example.model.PersonId;
import org.example.model.PersonName;
import org.example.model.RelationRole;
import org.example.model.RelationSubtype;
import org.example.model.SourceRecord;
import org.example.relation.RelationResolver;
import org.example.relation.ResolvedRelation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class StructuredPeopleAssembler {
    private static final String SOURCE = "source";
    private static final String DERIVED_SUBTYPE = "derived-subtype";
    private static final String DERIVED_RECIPROCAL = "derived-reciprocal";

    public StructuredPeopleDocument assemble(FactIndex facts, List<ResolvedRelation> relations) {
        Map<PersonId, PersonDraft> drafts = new LinkedHashMap<>();
        for (OwnerFacts person : facts.people()) {
            drafts.put(person.personId(), new PersonDraft(person.personId()));
        }
        for (OwnerFacts person : facts.people()) {
            drafts.get(person.personId()).name = canonicalOutputName(person.personId(), person.sourceRecords());
        }

        Map<PersonId, LinkedHashSet<Gender>> genderFacts = genderFacts(facts, relations);
        List<OutputRelation> outputRelations = outputRelations(relations, genderFacts);

        for (Map.Entry<PersonId, LinkedHashSet<Gender>> entry : genderFacts.entrySet()) {
            PersonDraft draft = drafts.get(entry.getKey());
            if (draft != null) {
                draft.genderFacts.addAll(entry.getValue());
            }
        }
        for (OutputRelation relation : outputRelations) {
            PersonDraft owner = drafts.get(relation.ownerId());
            if (owner != null && drafts.containsKey(relation.targetId())) {
                owner.putRelation(relation);
            }
        }
        assembleCounts(facts.people(), drafts);

        List<PersonDraft> sortedDrafts = drafts.values().stream()
            .sorted(Comparator.comparing(draft -> draft.personId.value()))
            .toList();
        Map<PersonId, StructuredPerson> peopleById = new LinkedHashMap<>();
        for (PersonDraft draft : sortedDrafts) {
            StructuredPerson person = draft.toStructuredPerson();
            peopleById.put(draft.personId, person);
        }
        for (PersonDraft draft : sortedDrafts) {
            StructuredPerson owner = peopleById.get(draft.personId);
            for (OutputRelation relation : draft.sortedRelations()) {
                StructuredPerson target = peopleById.get(relation.targetId());
                if (target != null) {
                    addRelation(owner.relations(), relation, target);
                }
            }
        }
        return StructuredPeopleDocument.of(new ArrayList<>(peopleById.values()));
    }

    private Map<PersonId, LinkedHashSet<Gender>> genderFacts(FactIndex facts, List<ResolvedRelation> relations) {
        Map<PersonId, LinkedHashSet<Gender>> genderFacts = new LinkedHashMap<>();
        for (OwnerFacts person : facts.people()) {
            for (SourceRecord record : person.sourceRecords()) {
                addGenderFact(genderFacts, person.personId(), record.gender());
                record.relations()
                    .forEach(relation -> addGenderFact(genderFacts, person.personId(), relation.subtype().ownerGenderRequirement()));
            }
        }
        for (ResolvedRelation relation : relations) {
            addGenderFact(genderFacts, relation.ownerId(), relation.relation().subtype().ownerGenderRequirement());
            addGenderFact(genderFacts, relation.targetId(), relation.relation().subtype().targetGenderRequirement());
        }
        return genderFacts;
    }

    private List<OutputRelation> outputRelations(
        List<ResolvedRelation> relations,
        Map<PersonId, LinkedHashSet<Gender>> genderFacts
    ) {
        List<OutputRelation> outputRelations = new ArrayList<>();
        for (ResolvedRelation relation : relations) {
            RelationSubtype subtype = RelationResolver.restoreSubtype(
                relation.relation().role(),
                relation.relation().subtype(),
                resolvedGender(genderFacts, relation.targetId())
            );
            outputRelations.add(new OutputRelation(
                relation.ownerId(),
                relation.relation().role(),
                subtype,
                relation.targetId(),
                subtype == relation.relation().subtype() ? SOURCE : DERIVED_SUBTYPE
            ));
        }
        for (OutputRelation relation : List.copyOf(outputRelations)) {
            RelationRole reciprocalRole = relation.role().reciprocal();
            outputRelations.add(new OutputRelation(
                relation.targetId(),
                reciprocalRole,
                RelationResolver.restoreSubtype(reciprocalRole, RelationSubtype.GENERIC, resolvedGender(genderFacts, relation.ownerId())),
                relation.ownerId(),
                DERIVED_RECIPROCAL
            ));
        }
        return outputRelations;
    }

    private PersonName canonicalOutputName(PersonId personId, List<SourceRecord> records) {
        return records.stream()
            .filter(record -> record.ownerIds().contains(personId))
            .map(SourceRecord::name)
            .filter(Objects::nonNull)
            .filter(PersonName::isFull)
            .findFirst()
            .or(() -> records.stream()
                .map(SourceRecord::name)
                .filter(Objects::nonNull)
                .filter(PersonName::isFull)
                .findFirst())
            .or(() -> Optional.ofNullable(combinedName(records)))
            .orElse(null);
    }

    private PersonName combinedName(List<SourceRecord> records) {
        String first = "";
        String last = "";
        for (SourceRecord record : records) {
            PersonName name = record.name();
            if (name == null) {
                continue;
            }
            if (first.isBlank() && !name.first().isBlank()) {
                first = name.first();
            }
            if (last.isBlank() && !name.last().isBlank()) {
                last = name.last();
            }
        }
        return first.isBlank() || last.isBlank() ? null : PersonName.ofParts(first, last);
    }

    private void assembleCounts(Iterable<OwnerFacts> people, Map<PersonId, PersonDraft> drafts) {
        for (OwnerFacts person : people) {
            PersonDraft draft = drafts.get(person.personId());
            Map<RelationRole, Integer> inferred = new EnumMap<>(RelationRole.class);
            for (RelationRole role : RelationRole.values()) {
                inferred.put(role, 0);
            }
            for (OutputRelation relation : draft.relations.values()) {
                inferred.merge(relation.role(), 1, Integer::sum);
            }
            Map<RelationRole, LinkedHashSet<Integer>> sourceCounts = sourceCounts(person);
            for (RelationRole role : RelationRole.values()) {
                int inferredValue = inferred.getOrDefault(role, 0);
                LinkedHashSet<Integer> values = sourceCounts.getOrDefault(role, new LinkedHashSet<>());
                if (values.isEmpty()) {
                    draft.counts.put(role, new StructuredCount(null, inferredValue, "source-unknown"));
                } else if (values.size() == 1) {
                    int sourceValue = values.getFirst();
                    String validation;
                    if (sourceValue == inferredValue) {
                        validation = "match";
                    } else if (sourceValue > inferredValue) {
                        validation = "under-inferred";
                    } else {
                        validation = "over-inferred";
                    }
                    draft.counts.put(role, new StructuredCount(sourceValue, inferredValue, validation));
                } else {
                    draft.counts.put(role, new StructuredCount(null, inferredValue, "ambiguous-source"));
                }
            }
        }
    }

    private Map<RelationRole, LinkedHashSet<Integer>> sourceCounts(OwnerFacts person) {
        Map<RelationRole, LinkedHashSet<Integer>> result = new LinkedHashMap<>();
        for (SourceRecord record : person.sourceRecords()) {
            for (CountFact count : record.counts()) {
                result.computeIfAbsent(count.role(), ignored -> new LinkedHashSet<>()).add(count.value());
            }
        }
        return result;
    }

    private void addRelation(StructuredRelations relations, OutputRelation relation, StructuredPerson target) {
        switch (relation.role()) {
            case SPOUSE -> relations.spouses().values().add(spouseRef(relation.subtype(), target, relation.provenance()));
            case PARENT -> relations.parents().values().add(parentRef(relation.subtype(), target, relation.provenance()));
            case SIBLING -> relations.siblings().values().add(siblingRef(relation.subtype(), target, relation.provenance()));
            case CHILD -> relations.children().values().add(childRef(relation.subtype(), target, relation.provenance()));
        }
    }

    private StructuredRelationRef spouseRef(RelationSubtype subtype, StructuredPerson target, String provenance) {
        return switch (subtype) {
            case WIFE -> new StructuredWifeRef(target, provenance);
            case HUSBAND -> new StructuredHusbandRef(target, provenance);
            default -> new StructuredSpouseRef(target, provenance);
        };
    }

    private StructuredRelationRef parentRef(RelationSubtype subtype, StructuredPerson target, String provenance) {
        return switch (subtype) {
            case FATHER -> new StructuredFatherRef(target, provenance);
            case MOTHER -> new StructuredMotherRef(target, provenance);
            default -> new StructuredParentRef(target, provenance);
        };
    }

    private StructuredRelationRef siblingRef(RelationSubtype subtype, StructuredPerson target, String provenance) {
        return switch (subtype) {
            case BROTHER -> new StructuredBrotherRef(target, provenance);
            case SISTER -> new StructuredSisterRef(target, provenance);
            default -> new StructuredSiblingRef(target, provenance);
        };
    }

    private StructuredRelationRef childRef(RelationSubtype subtype, StructuredPerson target, String provenance) {
        return switch (subtype) {
            case SON -> new StructuredSonRef(target, provenance);
            case DAUGHTER -> new StructuredDaughterRef(target, provenance);
            default -> new StructuredChildRef(target, provenance);
        };
    }

    private static void addGenderFact(Map<PersonId, LinkedHashSet<Gender>> genderFacts, PersonId personId, Gender gender) {
        if (gender != Gender.UNKNOWN) {
            genderFacts.computeIfAbsent(personId, ignored -> new LinkedHashSet<>()).add(gender);
        }
    }

    private Gender resolvedGender(Map<PersonId, LinkedHashSet<Gender>> genderFacts, PersonId personId) {
        LinkedHashSet<Gender> genders = genderFacts.getOrDefault(personId, new LinkedHashSet<>());
        return genders.size() == 1 ? genders.getFirst() : Gender.UNKNOWN;
    }

    private record OutputRelation(PersonId ownerId, RelationRole role, RelationSubtype subtype, PersonId targetId, String provenance) {}

    private record RelationKey(RelationRole role, PersonId targetId) {}

    private static final class PersonDraft {
        private final PersonId personId;
        private PersonName name;
        private final LinkedHashSet<Gender> genderFacts = new LinkedHashSet<>();
        private final Map<RelationKey, OutputRelation> relations = new LinkedHashMap<>();
        private final Map<RelationRole, StructuredCount> counts = new EnumMap<>(RelationRole.class);

        private PersonDraft(PersonId personId) {
            this.personId = personId;
        }

        private void putRelation(OutputRelation relation) {
            RelationKey key = new RelationKey(relation.role(), relation.targetId());
            OutputRelation existing = relations.get(key);
            if (existing == null) {
                relations.put(key, relation);
                return;
            }
            RelationSubtype subtype = existing.subtype();
            if (subtype == RelationSubtype.GENERIC && relation.subtype() != RelationSubtype.GENERIC) {
                subtype = relation.subtype();
            }
            String provenance = provenanceRank(relation.provenance()) < provenanceRank(existing.provenance())
                ? relation.provenance()
                : existing.provenance();
            if (subtype != existing.subtype() || !provenance.equals(existing.provenance())) {
                relations.put(key, new OutputRelation(existing.ownerId(), existing.role(), subtype, existing.targetId(), provenance));
            }
        }

        private int provenanceRank(String provenance) {
            return switch (provenance) {
                case SOURCE -> 0;
                case DERIVED_SUBTYPE -> 1;
                case DERIVED_RECIPROCAL -> 2;
                default -> 3;
            };
        }

        private StructuredPerson toStructuredPerson() {
            return new StructuredPerson(
                personId.value(),
                name == null ? null : new StructuredName(name.first(), name.last()),
                genderFacts.size() == 1 ? gender(genderFacts.getFirst()) : null,
                counts(),
                new StructuredRelations()
            );
        }

        private StructuredCounts counts() {
            return new StructuredCounts(
                counts.getOrDefault(RelationRole.SPOUSE, StructuredCount.unknown(0)),
                counts.getOrDefault(RelationRole.PARENT, StructuredCount.unknown(0)),
                counts.getOrDefault(RelationRole.SIBLING, StructuredCount.unknown(0)),
                counts.getOrDefault(RelationRole.CHILD, StructuredCount.unknown(0))
            );
        }

        private List<OutputRelation> sortedRelations() {
            return relations.values().stream()
                .sorted(Comparator.comparing(OutputRelation::role)
                    .thenComparing(relation -> relation.targetId().value()))
                .toList();
        }

        private StructuredGender gender(Gender gender) {
            return switch (gender) {
                case MALE -> new StructuredGender("male");
                case FEMALE -> new StructuredGender("female");
                case UNKNOWN -> null;
            };
        }
    }
}

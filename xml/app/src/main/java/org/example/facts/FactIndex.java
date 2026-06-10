package org.example.facts;

import org.example.model.CountFact;
import org.example.model.Gender;
import org.example.model.PersonId;
import org.example.model.PersonName;
import org.example.model.RelationMention;
import org.example.model.RelationRole;
import org.example.model.RelationSubtype;
import org.example.model.SourceRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FactIndex {
    private final Map<PersonId, OwnerFacts> peopleById = new LinkedHashMap<>();
    private final LinkedHashSet<SourceRecord> pendingRecords = new LinkedHashSet<>();
    private Map<PersonName, List<PersonId>> personIdsByName = Map.of();
    private Map<PersonId, LinkedHashSet<Gender>> genderFactsByPerson = Map.of();
    private Map<PersonId, Map<RelationRole, List<Integer>>> sourceCountsByPersonAndRole = Map.of();
    private Map<PersonId, List<RelationFact>> relationsFromPerson = Map.of();
    private Map<PersonId, List<RelationFact>> relationsToPerson = Map.of();
    private Map<PersonId, LinkedHashSet<PersonId>> parentsByChild = Map.of();

    public static FactIndex from(List<SourceRecord> records) {
        FactIndex facts = new FactIndex();
        for (SourceRecord record : records) {
            if (!record.invalidOwnerIdTokens().isEmpty() || record.ownerIds().size() > 1) {
                continue;
            }
            if (record.ownerIds().isEmpty()) {
                facts.pendingRecords.add(record);
            } else {
                facts.attach(record.ownerIds().getFirst(), record);
            }
        }
        facts.rebuildIndexes();
        return facts;
    }

    public Collection<OwnerFacts> people() {
        return peopleById.values();
    }

    public Set<PersonId> knownPersonIds() {
        return peopleById.keySet();
    }

    public boolean hasPerson(PersonId personId) {
        return peopleById.containsKey(personId);
    }

    public boolean hasPendingRecords() {
        return !pendingRecords.isEmpty();
    }

    public List<SourceRecord> pendingRecordsSorted() {
        return pendingRecords.stream()
            .sorted(Comparator.comparingInt(SourceRecord::recordNumber))
            .toList();
    }

    public boolean attachPending(SourceRecord record, PersonId ownerId) {
        OwnerFacts owner = peopleById.get(ownerId);
        pendingRecords.remove(record);
        if (owner == null) {
            return false;
        }
        owner.attach(record);
        return true;
    }

    public void discardPendingRecord(SourceRecord record) {
        pendingRecords.remove(record);
    }

    public List<PersonId> personIdsByName(PersonName name) {
        return personIdsByName.getOrDefault(name, List.of());
    }

    public LinkedHashSet<Gender> genderFacts(PersonId personId) {
        return genderFactsByPerson.getOrDefault(personId, new LinkedHashSet<>());
    }

    public List<Integer> sourceCounts(PersonId personId, RelationRole role) {
        return sourceCountsByPersonAndRole
            .getOrDefault(personId, Map.of())
            .getOrDefault(role, List.of());
    }

    public List<RelationFact> relationsFrom(PersonId personId) {
        return relationsFromPerson.getOrDefault(personId, List.of());
    }

    public List<RelationFact> relationsTo(PersonId personId) {
        return relationsToPerson.getOrDefault(personId, List.of());
    }

    public Map<PersonId, LinkedHashSet<PersonId>> parentsByChild() {
        return parentsByChild;
    }

    public Set<PersonId> childIds(PersonId personId) {
        Set<PersonId> children = new LinkedHashSet<>();
        for (RelationFact fact : relationsFrom(personId)) {
            if (fact.role() == RelationRole.CHILD) {
                children.add(fact.targetId());
            }
        }
        for (RelationFact fact : relationsTo(personId)) {
            if (fact.role() == RelationRole.PARENT) {
                children.add(fact.ownerId());
            }
        }
        return children;
    }

    public Set<PersonId> spouses(PersonId personId) {
        Set<PersonId> result = new LinkedHashSet<>();
        for (RelationFact fact : relationsFrom(personId)) {
            if (fact.role() == RelationRole.SPOUSE) {
                result.add(fact.targetId());
            }
        }
        for (RelationFact fact : relationsTo(personId)) {
            if (fact.role() == RelationRole.SPOUSE) {
                result.add(fact.ownerId());
            }
        }
        return result;
    }

    public Map<RelationRole, Integer> normalizedRelationCounts(PersonId ownerId) {
        Map<RelationRole, LinkedHashSet<PersonId>> targetsByRole = new LinkedHashMap<>();
        for (RelationFact fact : relationsFrom(ownerId)) {
            targetsByRole.computeIfAbsent(fact.role(), ignored -> new LinkedHashSet<>()).add(fact.targetId());
        }
        for (RelationFact fact : relationsTo(ownerId)) {
            targetsByRole.computeIfAbsent(fact.role().reciprocal(), ignored -> new LinkedHashSet<>()).add(fact.ownerId());
        }

        Map<RelationRole, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<RelationRole, LinkedHashSet<PersonId>> entry : targetsByRole.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().size());
        }
        return counts;
    }

    public void rebuildIndexes() {
        Map<PersonName, LinkedHashSet<PersonId>> idsByName = new LinkedHashMap<>();
        Map<PersonId, LinkedHashSet<Gender>> gendersByPerson = new LinkedHashMap<>();
        Map<PersonId, Map<RelationRole, List<Integer>>> countsByPersonAndRole = new LinkedHashMap<>();
        Map<PersonId, List<RelationFact>> fromPerson = new LinkedHashMap<>();
        Map<PersonId, List<RelationFact>> toPerson = new LinkedHashMap<>();
        Map<PersonId, LinkedHashSet<PersonId>> parents = new LinkedHashMap<>();

        for (OwnerFacts person : peopleById.values()) {
            for (SourceRecord record : person.sourceRecords()) {
                if (record.name() != null) {
                    idsByName.computeIfAbsent(record.name(), ignored -> new LinkedHashSet<>())
                        .add(person.personId());
                }
                addGenderFact(gendersByPerson, person.personId(), record.gender());

                for (CountFact count : record.counts()) {
                    countsByPersonAndRole
                        .computeIfAbsent(person.personId(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(count.role(), ignored -> new ArrayList<>())
                        .add(count.value());
                }

                for (RelationMention relation : record.relations()) {
                    addGenderFact(gendersByPerson, person.personId(), relation.subtype().ownerGenderRequirement());
                    if (relation.targetId() == null) {
                        continue;
                    }
                    PersonId targetId = relation.targetId();
                    addGenderFact(gendersByPerson, targetId, relation.subtype().targetGenderRequirement());
                    RelationFact fact = new RelationFact(
                        person.personId(),
                        relation.role(),
                        relation.subtype(),
                        targetId
                    );
                    fromPerson.computeIfAbsent(person.personId(), ignored -> new ArrayList<>()).add(fact);
                    toPerson.computeIfAbsent(targetId, ignored -> new ArrayList<>()).add(fact);
                    switch (relation.role()) {
                        case CHILD -> parents.computeIfAbsent(targetId, ignored -> new LinkedHashSet<>())
                            .add(person.personId());
                        case PARENT -> parents.computeIfAbsent(person.personId(), ignored -> new LinkedHashSet<>())
                            .add(targetId);
                        case SPOUSE, SIBLING -> {
                        }
                    }
                }
            }
        }

        personIdsByName = toListMap(idsByName);
        genderFactsByPerson = gendersByPerson;
        sourceCountsByPersonAndRole = countsByPersonAndRole;
        relationsFromPerson = fromPerson;
        relationsToPerson = toPerson;
        parentsByChild = parents;
    }

    private void attach(PersonId ownerId, SourceRecord record) {
        peopleById.computeIfAbsent(ownerId, OwnerFacts::new).attach(record);
    }

    private static void addGenderFact(Map<PersonId, LinkedHashSet<Gender>> facts, PersonId personId, Gender gender) {
        if (gender != Gender.UNKNOWN) {
            facts.computeIfAbsent(personId, ignored -> new LinkedHashSet<>()).add(gender);
        }
    }

    private static <K, V> Map<K, List<V>> toListMap(Map<K, LinkedHashSet<V>> source) {
        Map<K, List<V>> result = new LinkedHashMap<>();
        for (Map.Entry<K, LinkedHashSet<V>> entry : source.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }

    public record RelationFact(PersonId ownerId, RelationRole role, RelationSubtype subtype, PersonId targetId) {}

    public static final class OwnerFacts {
        private final PersonId personId;
        private PersonName name;
        private final List<SourceRecord> sourceRecords = new ArrayList<>();

        private OwnerFacts(PersonId personId) {
            this.personId = personId;
        }

        public PersonId personId() {
            return personId;
        }

        public PersonName name() {
            return name;
        }

        public List<SourceRecord> sourceRecords() {
            return sourceRecords;
        }

        private void attach(SourceRecord record) {
            sourceRecords.add(record);
            if (name == null && record.name() != null) {
                name = record.name();
            }
        }
    }
}

package org.example.relation;

import org.example.facts.FactIndex;
import org.example.facts.FactIndex.OwnerFacts;
import org.example.facts.FactIndex.RelationFact;
import org.example.model.Gender;
import org.example.model.PersonId;
import org.example.model.RelationMention;
import org.example.model.RelationRole;
import org.example.model.RelationSubtype;
import org.example.model.SourceRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

public final class RelationResolver {
    public List<ResolvedRelation> resolve(FactIndex facts) {
        Set<PersonId> knownPeople = facts.knownPersonIds();
        Map<GraphEdgeKey, ResolvedRelation> directRelations = new LinkedHashMap<>();
        Map<GraphEdgeKey, ResolvedRelation> byNameCandidates = new LinkedHashMap<>();
        for (OwnerFacts person : facts.people()) {
            for (SourceRecord record : person.sourceRecords()) {
                for (RelationMention relation : record.relations()) {
                    if (relation.targetId() != null
                        && knownPeople.contains(relation.targetId())
                        && !hasRelationTargetConflict(person.personId(), relation, relation.targetId(), facts)) {
                        putPreferred(directRelations, person.personId(), relation, relation.targetId());
                    }
                }
            }
        }
        for (OwnerFacts person : facts.people()) {
            for (SourceRecord record : person.sourceRecords()) {
                for (RelationMention relation : record.relations()) {
                    if (relation.targetName() != null && relation.targetName().isFull()) {
                        TargetResolution resolution = resolveTarget(person.personId(), relation, facts);
                        if (resolution.selectedTargetId() != null && knownPeople.contains(resolution.selectedTargetId())) {
                            putPreferred(byNameCandidates, person.personId(), relation, resolution.selectedTargetId());
                        }
                    }
                }
            }
        }
        return acceptedAfterBatchPruning(
            facts,
            List.copyOf(directRelations.values()),
            List.copyOf(byNameCandidates.values())
        );
    }

    private void putPreferred(
        Map<GraphEdgeKey, ResolvedRelation> relations,
        PersonId ownerId,
        RelationMention relation,
        PersonId targetId
    ) {
        GraphEdgeKey key = new GraphEdgeKey(ownerId, relation.role(), targetId);
        ResolvedRelation existing = relations.get(key);
        if (existing == null
            || (existing.relation().subtype() == RelationSubtype.GENERIC
                && relation.subtype() != RelationSubtype.GENERIC)) {
            relations.put(key, new ResolvedRelation(ownerId, relation, targetId));
        }
    }

    private List<ResolvedRelation> acceptedAfterBatchPruning(
        FactIndex facts,
        List<ResolvedRelation> directRelations,
        List<ResolvedRelation> byNameCandidates
    ) {
        List<ResolvedRelation> accepted = new ArrayList<>(directRelations);
        BatchState batchState = BatchState.from(facts);
        for (ResolvedRelation candidate : byNameCandidates) {
            Edge edge = Edge.from(candidate);
            if (batchState.canAdd(edge)) {
                batchState.add(edge);
                accepted.add(candidate);
            }
        }
        return accepted;
    }

    public TargetResolution resolveTarget(
        PersonId ownerId,
        RelationMention relation,
        FactIndex facts
    ) {
        if (relation.targetName() == null || !relation.targetName().isFull()) {
            return new TargetResolution(null, false, false);
        }

        List<PersonId> candidates = facts.personIdsByName(relation.targetName())
            .stream()
            .sorted(Comparator.comparing(PersonId::value))
            .toList();
        if (candidates.isEmpty()) {
            return new TargetResolution(null, false, false);
        }

        List<PersonId> viable = new ArrayList<>();
        LinkedHashSet<PersonId> supported = new LinkedHashSet<>();
        for (PersonId candidate : candidates) {
            if (hasRelationTargetConflict(ownerId, relation, candidate, facts)) {
                continue;
            }
            viable.add(candidate);
            if (hasCompatibleReverseEdge(ownerId, relation, candidate, facts)
                || (relation.role() == RelationRole.SPOUSE && shareChild(ownerId, candidate, facts))) {
                supported.add(candidate);
            }
        }

        if (viable.isEmpty()) {
            return new TargetResolution(null, false, true);
        }
        if (candidates.size() == 1) {
            return new TargetResolution(viable.getFirst(), supported.contains(viable.getFirst()), false);
        }
        if (supported.size() == 1) {
            return new TargetResolution(supported.getFirst(), true, false);
        }
        return new TargetResolution(null, false, false);
    }

    public boolean hasMatchingRelationFact(FactIndex facts, PersonId candidate, RelationMention relation) {
        PersonId targetId = relation.targetId();
        return facts.relationsFrom(candidate).stream()
            .anyMatch(fact -> fact.targetId().equals(targetId) && fact.role() == relation.role())
            || facts.relationsTo(candidate).stream()
            .anyMatch(fact -> fact.ownerId().equals(targetId) && fact.role() == relation.role().reciprocal());
    }

    public boolean hasResolvedRelationConflict(
        PersonId ownerId,
        RelationMention relation,
        PersonId targetId,
        FactIndex facts
    ) {
        return hasRelationTargetConflict(ownerId, relation, targetId, facts);
    }

    private boolean hasRelationTargetConflict(
        PersonId ownerId,
        RelationMention relation,
        PersonId targetId,
        FactIndex facts
    ) {
        if (ownerId.equals(targetId)) {
            return true;
        }

        Gender targetGender = resolvedGender(facts.genderFacts(targetId));
        RelationSubtype subtype = restoreSubtype(relation.role(), relation.subtype(), targetGender);
        Gender requiredGender = subtype.targetGenderRequirement();
        if (requiredGender != Gender.UNKNOWN && targetGender != Gender.UNKNOWN && targetGender != requiredGender) {
            return true;
        }
        if (genderConflict(subtype.ownerGenderRequirement(), facts.genderFacts(ownerId))) {
            return true;
        }
        if (directRelationConflict(relation, ownerId, targetId, facts)) {
            return true;
        }
        if (spouseChildCountConflict(facts, ownerId, relation.role(), targetId)) {
            return true;
        }

        ParentChildKey parentChild = parentChildKey(ownerId, relation.role(), targetId);
        if (parentChild != null) {
            Set<PersonId> parents = new LinkedHashSet<>(
                facts.parentsByChild().getOrDefault(parentChild.childId(), new LinkedHashSet<>())
            );
            parents.add(parentChild.parentId());
            if (parents.size() > 2 || coparentSpouseConflict(facts, parents)) {
                return true;
            }
        }
        return relation.role() == RelationRole.SPOUSE && coparentSpouseConflictForSpouseEdge(facts, ownerId, targetId);
    }

    private boolean spouseChildCountConflict(
        FactIndex facts,
        PersonId ownerId,
        RelationRole role,
        PersonId targetId
    ) {
        if (role == RelationRole.SPOUSE) {
            return spouseChildCountConflict(facts, ownerId, targetId, null);
        }
        ParentChildKey parentChild = parentChildKey(ownerId, role, targetId);
        if (parentChild == null) {
            return false;
        }
        for (PersonId spouse : facts.spouses(parentChild.parentId())) {
            if (spouseChildCountConflict(facts, parentChild.parentId(), spouse, parentChild)) {
                return true;
            }
        }
        return false;
    }

    private boolean spouseChildCountConflict(
        FactIndex facts,
        PersonId left,
        PersonId right,
        ParentChildKey proposedParentChild
    ) {
        OptionalInt leftCount = uniqueSourceCount(facts, left, RelationRole.CHILD);
        OptionalInt rightCount = uniqueSourceCount(facts, right, RelationRole.CHILD);
        if (leftCount.isEmpty() || rightCount.isEmpty()) {
            return false;
        }
        if (leftCount.orElseThrow() != rightCount.orElseThrow()) {
            return true;
        }

        int count = leftCount.orElseThrow();
        Set<PersonId> leftChildren = childIds(facts, left, proposedParentChild);
        Set<PersonId> rightChildren = childIds(facts, right, proposedParentChild);
        return leftChildren.size() == count
            && rightChildren.size() == count
            && !leftChildren.equals(rightChildren);
    }

    private OptionalInt uniqueSourceCount(FactIndex facts, PersonId personId, RelationRole role) {
        LinkedHashSet<Integer> values = new LinkedHashSet<>(facts.sourceCounts(personId, role));
        return values.size() == 1 ? OptionalInt.of(values.getFirst()) : OptionalInt.empty();
    }

    private Set<PersonId> childIds(FactIndex facts, PersonId parentId, ParentChildKey proposedParentChild) {
        Set<PersonId> children = facts.childIds(parentId);
        if (proposedParentChild != null && proposedParentChild.parentId().equals(parentId)) {
            children.add(proposedParentChild.childId());
        }
        return children;
    }

    private boolean hasCompatibleReverseEdge(
        PersonId ownerId,
        RelationMention relation,
        PersonId candidate,
        FactIndex facts
    ) {
        return facts.relationsFrom(candidate).stream()
            .anyMatch(fact -> fact.targetId().equals(ownerId)
                && fact.role() == relation.role().reciprocal()
                && relation.subtype().reverseCompatible(fact.subtype()));
    }

    private boolean directRelationConflict(
        RelationMention proposedRelation,
        PersonId candidate,
        PersonId target,
        FactIndex facts
    ) {
        for (RelationFact existing : facts.relationsFrom(candidate)) {
            if (existing.targetId().equals(target) && existing.role() != proposedRelation.role()) {
                return true;
            }
        }
        for (RelationFact existing : facts.relationsTo(candidate)) {
            if (existing.ownerId().equals(target) && existing.role() != proposedRelation.role().reciprocal()) {
                return true;
            }
        }
        return false;
    }

    private boolean shareChild(PersonId left, PersonId right, FactIndex facts) {
        Set<PersonId> leftChildren = facts.childIds(left);
        Set<PersonId> rightChildren = facts.childIds(right);
        return leftChildren.stream().anyMatch(rightChildren::contains);
    }

    private ParentChildKey parentChildKey(PersonId ownerId, RelationRole role, PersonId targetId) {
        return switch (role) {
            case CHILD -> new ParentChildKey(ownerId, targetId);
            case PARENT -> new ParentChildKey(targetId, ownerId);
            case SPOUSE, SIBLING -> null;
        };
    }

    private boolean coparentSpouseConflict(FactIndex facts, Set<PersonId> parents) {
        if (parents.size() != 2) {
            return false;
        }
        List<PersonId> parentList = new ArrayList<>(parents);
        return coparentSpouseConflict(facts, new PersonPair(parentList.get(0), parentList.get(1)));
    }

    private boolean coparentSpouseConflictForSpouseEdge(FactIndex facts, PersonId leftSpouse, PersonId rightSpouse) {
        Set<PersonPair> parentPairs = new LinkedHashSet<>();
        for (Set<PersonId> parents : facts.parentsByChild().values()) {
            if (parents.size() == 2 && (parents.contains(leftSpouse) || parents.contains(rightSpouse))) {
                List<PersonId> parentList = new ArrayList<>(parents);
                parentPairs.add(new PersonPair(parentList.get(0), parentList.get(1)));
            }
        }
        for (PersonPair parentPair : parentPairs) {
            Set<PersonId> leftSpouses = spousesWithProposed(facts, parentPair.left(), leftSpouse, rightSpouse);
            Set<PersonId> rightSpouses = spousesWithProposed(facts, parentPair.right(), leftSpouse, rightSpouse);
            boolean leftConflicts = !leftSpouses.isEmpty() && !leftSpouses.equals(Set.of(parentPair.right()));
            boolean rightConflicts = !rightSpouses.isEmpty() && !rightSpouses.equals(Set.of(parentPair.left()));
            if (leftConflicts || rightConflicts) {
                return true;
            }
        }
        return false;
    }

    private Set<PersonId> spousesWithProposed(
        FactIndex facts,
        PersonId personId,
        PersonId leftSpouse,
        PersonId rightSpouse
    ) {
        Set<PersonId> spouses = facts.spouses(personId);
        if (personId.equals(leftSpouse)) {
            spouses.add(rightSpouse);
        }
        if (personId.equals(rightSpouse)) {
            spouses.add(leftSpouse);
        }
        return spouses;
    }

    private boolean coparentSpouseConflict(FactIndex facts, PersonPair parentPair) {
        Set<PersonId> leftSpouses = facts.spouses(parentPair.left());
        Set<PersonId> rightSpouses = facts.spouses(parentPair.right());
        boolean leftConflicts = !leftSpouses.isEmpty() && !leftSpouses.equals(Set.of(parentPair.right()));
        boolean rightConflicts = !rightSpouses.isEmpty() && !rightSpouses.equals(Set.of(parentPair.left()));
        return leftConflicts || rightConflicts;
    }

    public static RelationSubtype restoreSubtype(RelationRole role, RelationSubtype original, Gender targetGender) {
        if (original != RelationSubtype.GENERIC || targetGender == Gender.UNKNOWN) {
            return original;
        }
        return switch (role) {
            case SPOUSE -> targetGender == Gender.FEMALE ? RelationSubtype.WIFE : RelationSubtype.HUSBAND;
            case PARENT -> targetGender == Gender.FEMALE ? RelationSubtype.MOTHER : RelationSubtype.FATHER;
            case SIBLING -> targetGender == Gender.FEMALE ? RelationSubtype.SISTER : RelationSubtype.BROTHER;
            case CHILD -> targetGender == Gender.FEMALE ? RelationSubtype.DAUGHTER : RelationSubtype.SON;
        };
    }

    private static Gender resolvedGender(LinkedHashSet<Gender> genders) {
        return genders.size() == 1 ? genders.getFirst() : Gender.UNKNOWN;
    }

    private boolean genderConflict(Gender requiredGender, Iterable<Gender> facts) {
        if (requiredGender == Gender.UNKNOWN) {
            return false;
        }
        for (Gender gender : facts) {
            if (gender != requiredGender) {
                return true;
            }
        }
        return false;
    }

    private record Edge(PersonId ownerId, RelationRole role, RelationSubtype subtype, PersonId targetId) {
        private static Edge from(ResolvedRelation relation) {
            return new Edge(
                relation.ownerId(),
                relation.relation().role(),
                relation.relation().subtype(),
                relation.targetId()
            );
        }
    }

    private record GraphEdgeKey(PersonId ownerId, RelationRole role, PersonId targetId) {}

    private record RelationKey(PersonId ownerId, PersonId targetId) {}

    private record ParentChildKey(PersonId parentId, PersonId childId) {}

    private record PersonPair(PersonId left, PersonId right) {
        private PersonPair {
            if (left.value().compareTo(right.value()) > 0) {
                PersonId previousLeft = left;
                left = right;
                right = previousLeft;
            }
        }

        private boolean contains(PersonId personId) {
            return left.equals(personId) || right.equals(personId);
        }
    }

    private static final class BatchState {
        private final Set<PersonId> knownPeople = new LinkedHashSet<>();
        private final Map<PersonId, LinkedHashSet<Gender>> gendersByPerson = new LinkedHashMap<>();
        private final Map<PersonId, OptionalInt> sourceChildCounts = new LinkedHashMap<>();
        private final Map<RelationKey, List<Edge>> edgesByOwnerTarget = new LinkedHashMap<>();
        private final Map<PersonId, Set<PersonId>> parentsByChild = new LinkedHashMap<>();
        private final Map<PersonId, Set<PersonId>> childrenByParent = new LinkedHashMap<>();
        private final Map<PersonId, Set<PersonId>> ancestorsByPerson = new LinkedHashMap<>();
        private final Map<RelationRole, Set<PersonPair>> pairsByRole = new LinkedHashMap<>();
        private final Map<PersonId, Set<PersonId>> spousesByPerson = new LinkedHashMap<>();
        private final Map<PersonId, List<Edge>> edgesByPerson = new LinkedHashMap<>();
        private final List<Edge> edges = new ArrayList<>();
        private Set<PersonPair> exactParentPairsCache;
        private boolean ancestorsBuilt;

        private static BatchState from(FactIndex facts) {
            BatchState state = new BatchState();
            state.knownPeople.addAll(facts.knownPersonIds());
            for (PersonId personId : facts.knownPersonIds()) {
                state.gendersByPerson.put(personId, new LinkedHashSet<>(facts.genderFacts(personId)));
                state.sourceChildCounts.put(personId, uniqueSourceCount(facts, personId));
            }
            Map<GraphEdgeKey, Edge> graphEdges = new LinkedHashMap<>();
            for (OwnerFacts person : facts.people()) {
                for (RelationFact fact : facts.relationsFrom(person.personId())) {
                    Edge edge = new Edge(fact.ownerId(), fact.role(), fact.subtype(), fact.targetId());
                    if (facts.hasPerson(fact.targetId())) {
                        graphEdges.merge(new GraphEdgeKey(edge.ownerId(), edge.role(), edge.targetId()), edge, BatchState::preferredEdge);
                    }
                }
            }
            graphEdges.values().forEach(state::add);
            state.rebuildAncestors();
            return state;
        }

        private static Edge preferredEdge(Edge existing, Edge candidate) {
            return existing.subtype() == RelationSubtype.GENERIC && candidate.subtype() != RelationSubtype.GENERIC
                ? candidate
                : existing;
        }

        private boolean canAdd(Edge edge) {
            return knownPeople.contains(edge.targetId())
                && !edge.ownerId().equals(edge.targetId())
                && !genderConflict(edge)
                && !roleConflict(edge)
                && !parentConflict(edge)
                && !crossRoleConflict(edge)
                && !siblingParentConflict(edge)
                && !coparentSpouseConflict(edge)
                && !spouseChildCountConflict(edge);
        }

        private void add(Edge edge) {
            edges.add(edge);
            addGender(edge.ownerId(), edge.subtype().ownerGenderRequirement());
            addGender(edge.targetId(), edge.subtype().targetGenderRequirement());
            edgesByOwnerTarget
                .computeIfAbsent(new RelationKey(edge.ownerId(), edge.targetId()), ignored -> new ArrayList<>())
                .add(edge);
            edgesByPerson.computeIfAbsent(edge.ownerId(), ignored -> new ArrayList<>()).add(edge);
            edgesByPerson.computeIfAbsent(edge.targetId(), ignored -> new ArrayList<>()).add(edge);
            pairsByRole
                .computeIfAbsent(edge.role(), ignored -> new LinkedHashSet<>())
                .add(new PersonPair(edge.ownerId(), edge.targetId()));
            switch (edge.role()) {
                case CHILD -> addParentChild(edge.ownerId(), edge.targetId());
                case PARENT -> addParentChild(edge.targetId(), edge.ownerId());
                case SPOUSE -> {
                    spousesByPerson.computeIfAbsent(edge.ownerId(), ignored -> new LinkedHashSet<>()).add(edge.targetId());
                    spousesByPerson.computeIfAbsent(edge.targetId(), ignored -> new LinkedHashSet<>()).add(edge.ownerId());
                }
                case SIBLING -> {
                }
            }
        }

        private void addParentChild(PersonId parentId, PersonId childId) {
            boolean changed = parentsByChild
                .computeIfAbsent(childId, ignored -> new LinkedHashSet<>())
                .add(parentId);
            childrenByParent
                .computeIfAbsent(parentId, ignored -> new LinkedHashSet<>())
                .add(childId);
            if (changed) {
                exactParentPairsCache = null;
                if (ancestorsBuilt) {
                    propagateAncestors(parentId, childId);
                }
            }
        }

        private boolean genderConflict(Edge candidate) {
            Set<Edge> edgesToRecheck = new LinkedHashSet<>();
            edgesToRecheck.add(candidate);
            for (PersonId personId : changedGenderPersons(candidate)) {
                edgesToRecheck.addAll(edgesByPerson.getOrDefault(personId, List.of()));
            }
            for (Edge edge : edgesToRecheck) {
                if (genderConflictForEdge(edge, candidate)) {
                    return true;
                }
            }
            return false;
        }

        private Set<PersonId> changedGenderPersons(Edge edge) {
            Set<PersonId> result = new LinkedHashSet<>();
            if (edge.subtype().ownerGenderRequirement() != Gender.UNKNOWN) {
                result.add(edge.ownerId());
            }
            if (edge.subtype().targetGenderRequirement() != Gender.UNKNOWN) {
                result.add(edge.targetId());
            }
            return result;
        }

        private boolean genderConflictForEdge(Edge edge, Edge candidate) {
            Edge effective = effectiveEdge(edge, candidate);
            return genderConflict(effective.ownerId(), effective.subtype().ownerGenderRequirement(), candidate)
                || genderConflict(effective.targetId(), effective.subtype().targetGenderRequirement(), candidate);
        }

        private boolean genderConflict(
            PersonId personId,
            Gender requiredGender,
            Edge candidate
        ) {
            if (requiredGender == Gender.UNKNOWN) {
                return false;
            }
            for (Gender gender : projectedGenderFacts(personId, candidate)) {
                if (gender != requiredGender) {
                    return true;
                }
            }
            return false;
        }

        private void addGender(PersonId personId, Gender gender) {
            if (gender != Gender.UNKNOWN) {
                gendersByPerson.computeIfAbsent(personId, ignored -> new LinkedHashSet<>()).add(gender);
            }
        }

        private boolean roleConflict(Edge edge) {
            Edge effectiveEdge = effectiveEdge(edge, edge);
            for (Edge existing : edgesByOwnerTarget.getOrDefault(new RelationKey(edge.ownerId(), edge.targetId()), List.of())) {
                if (existing.role() != edge.role()) {
                    return true;
                }
            }
            for (Edge reverse : edgesByOwnerTarget.getOrDefault(new RelationKey(edge.targetId(), edge.ownerId()), List.of())) {
                Edge effectiveReverse = effectiveEdge(reverse, edge);
                if (effectiveReverse.role() != effectiveEdge.role().reciprocal()
                    || !effectiveEdge.subtype().reverseCompatible(effectiveReverse.subtype())) {
                    return true;
                }
            }
            return false;
        }

        private Edge effectiveEdge(Edge edge, Edge candidate) {
            Gender targetGender = resolvedGender(projectedGenderFacts(edge.targetId(), candidate));
            return new Edge(edge.ownerId(), edge.role(), restoreSubtype(edge.role(), edge.subtype(), targetGender), edge.targetId());
        }

        private LinkedHashSet<Gender> projectedGenderFacts(PersonId personId, Edge candidate) {
            LinkedHashSet<Gender> facts = new LinkedHashSet<>(gendersByPerson.getOrDefault(personId, new LinkedHashSet<>()));
            if (personId.equals(candidate.ownerId()) && candidate.subtype().ownerGenderRequirement() != Gender.UNKNOWN) {
                facts.add(candidate.subtype().ownerGenderRequirement());
            }
            if (personId.equals(candidate.targetId()) && candidate.subtype().targetGenderRequirement() != Gender.UNKNOWN) {
                facts.add(candidate.subtype().targetGenderRequirement());
            }
            return facts;
        }

        private boolean parentConflict(Edge edge) {
            ParentChildKey parentChild = parentChildKey(edge);
            if (parentChild == null) {
                return false;
            }
            Set<PersonId> parents = new LinkedHashSet<>(parentsByChild.getOrDefault(parentChild.childId(), Set.of()));
            parents.add(parentChild.parentId());
            if (parents.size() > 2 || hasParentPath(parentChild.parentId(), parentChild.childId())) {
                return true;
            }
            return parents.size() == 2 && pairs(RelationRole.SIBLING).contains(pairFrom(parents));
        }

        private boolean crossRoleConflict(Edge edge) {
            PersonPair pair = new PersonPair(edge.ownerId(), edge.targetId());
            return switch (edge.role()) {
                case SPOUSE -> pairs(RelationRole.SIBLING).contains(pair) || ancestryRelated(pair);
                case SIBLING -> pairs(RelationRole.SPOUSE).contains(pair)
                    || pairs(RelationRole.PARENT).contains(pair)
                    || pairs(RelationRole.CHILD).contains(pair)
                    || ancestryRelated(pair);
                case PARENT, CHILD -> pairs(RelationRole.SIBLING).contains(pair)
                    || pairs(RelationRole.SPOUSE).contains(pair);
            };
        }

        private boolean siblingParentConflict(Edge edge) {
            if (edge.role() != RelationRole.SIBLING) {
                return false;
            }
            Set<PersonId> leftParents = parentsByChild.getOrDefault(edge.ownerId(), Set.of());
            Set<PersonId> rightParents = parentsByChild.getOrDefault(edge.targetId(), Set.of());
            Set<PersonId> parentUnion = new LinkedHashSet<>(leftParents);
            parentUnion.addAll(rightParents);
            if (parentUnion.size() > 2) {
                return true;
            }
            if (leftParents.size() == 1 && rightParents.size() == 1) {
                PersonId leftParent = leftParents.iterator().next();
                PersonId rightParent = rightParents.iterator().next();
                return !leftParent.equals(rightParent)
                    && !pairs(RelationRole.SPOUSE).contains(new PersonPair(leftParent, rightParent))
                    && !exactParentPairs().contains(new PersonPair(leftParent, rightParent));
            }
            return false;
        }

        private boolean coparentSpouseConflict(Edge edge) {
            if (edge.role() == RelationRole.SPOUSE) {
                return coparentSpouseConflictForSpouse(edge.ownerId(), edge.targetId());
            }
            ParentChildKey parentChild = parentChildKey(edge);
            if (parentChild == null) {
                return false;
            }
            Set<PersonId> parents = new LinkedHashSet<>(parentsByChild.getOrDefault(parentChild.childId(), Set.of()));
            parents.add(parentChild.parentId());
            if (parents.size() != 2) {
                return false;
            }
            return coparentSpouseConflict(pairFrom(parents));
        }

        private boolean coparentSpouseConflictForSpouse(PersonId leftSpouse, PersonId rightSpouse) {
            for (PersonPair parentPair : exactParentPairs()) {
                if (!parentPair.contains(leftSpouse) && !parentPair.contains(rightSpouse)) {
                    continue;
                }
                Set<PersonId> leftSpouses = spousesWithProposed(parentPair.left(), leftSpouse, rightSpouse);
                Set<PersonId> rightSpouses = spousesWithProposed(parentPair.right(), leftSpouse, rightSpouse);
                boolean leftConflicts = !leftSpouses.isEmpty() && !leftSpouses.equals(Set.of(parentPair.right()));
                boolean rightConflicts = !rightSpouses.isEmpty() && !rightSpouses.equals(Set.of(parentPair.left()));
                if (leftConflicts || rightConflicts) {
                    return true;
                }
            }
            return false;
        }

        private Set<PersonId> spousesWithProposed(PersonId personId, PersonId leftSpouse, PersonId rightSpouse) {
            Set<PersonId> spouses = new LinkedHashSet<>(spousesByPerson.getOrDefault(personId, Set.of()));
            if (personId.equals(leftSpouse)) {
                spouses.add(rightSpouse);
            }
            if (personId.equals(rightSpouse)) {
                spouses.add(leftSpouse);
            }
            return spouses;
        }

        private boolean coparentSpouseConflict(PersonPair parentPair) {
            Set<PersonId> leftSpouses = spousesByPerson.getOrDefault(parentPair.left(), Set.of());
            Set<PersonId> rightSpouses = spousesByPerson.getOrDefault(parentPair.right(), Set.of());
            return (!leftSpouses.isEmpty() && !leftSpouses.equals(Set.of(parentPair.right())))
                || (!rightSpouses.isEmpty() && !rightSpouses.equals(Set.of(parentPair.left())));
        }

        private boolean spouseChildCountConflict(Edge edge) {
            if (edge.role() == RelationRole.SPOUSE) {
                return spouseChildCountConflict(edge.ownerId(), edge.targetId(), edge);
            }
            ParentChildKey parentChild = parentChildKey(edge);
            if (parentChild == null) {
                return false;
            }
            for (PersonId spouse : spousesByPerson.getOrDefault(parentChild.parentId(), Set.of())) {
                if (spouseChildCountConflict(parentChild.parentId(), spouse, edge)) {
                    return true;
                }
            }
            return false;
        }

        private boolean spouseChildCountConflict(PersonId left, PersonId right, Edge candidate) {
            OptionalInt leftCount = sourceChildCounts.getOrDefault(left, OptionalInt.empty());
            OptionalInt rightCount = sourceChildCounts.getOrDefault(right, OptionalInt.empty());
            if (leftCount.isEmpty() || rightCount.isEmpty()) {
                return false;
            }
            if (leftCount.orElseThrow() != rightCount.orElseThrow()) {
                return true;
            }

            int count = leftCount.orElseThrow();
            Set<PersonId> leftChildren = childIds(left, candidate);
            Set<PersonId> rightChildren = childIds(right, candidate);
            return leftChildren.size() == count
                && rightChildren.size() == count
                && !leftChildren.equals(rightChildren);
        }

        private Set<PersonId> childIds(PersonId parentId, Edge candidate) {
            Set<PersonId> children = new LinkedHashSet<>(childrenByParent.getOrDefault(parentId, Set.of()));
            ParentChildKey parentChild = parentChildKey(candidate);
            if (parentChild != null && parentChild.parentId().equals(parentId)) {
                children.add(parentChild.childId());
            }
            return children;
        }

        private boolean ancestryRelated(PersonPair pair) {
            return hasParentPath(pair.left(), pair.right()) || hasParentPath(pair.right(), pair.left());
        }

        private boolean hasParentPath(PersonId start, PersonId target) {
            if (!ancestorsBuilt) {
                rebuildAncestors();
            }
            return ancestorsByPerson.getOrDefault(start, Set.of()).contains(target);
        }

        private void rebuildAncestors() {
            ancestorsByPerson.clear();
            for (PersonId personId : parentsByChild.keySet()) {
                ancestorsByPerson.put(personId, computeAncestors(personId));
            }
            ancestorsBuilt = true;
        }

        private Set<PersonId> computeAncestors(PersonId personId) {
            Set<PersonId> ancestors = new LinkedHashSet<>();
            List<PersonId> stack = new ArrayList<>(parentsByChild.getOrDefault(personId, Set.of()));
            while (!stack.isEmpty()) {
                PersonId current = stack.removeLast();
                if (!ancestors.add(current)) {
                    continue;
                }
                stack.addAll(parentsByChild.getOrDefault(current, Set.of()));
            }
            return ancestors;
        }

        private void propagateAncestors(PersonId parentId, PersonId childId) {
            Set<PersonId> inheritedAncestors = new LinkedHashSet<>();
            inheritedAncestors.add(parentId);
            inheritedAncestors.addAll(ancestorsByPerson.getOrDefault(parentId, Set.of()));
            List<PersonId> stack = new ArrayList<>(List.of(childId));
            while (!stack.isEmpty()) {
                PersonId current = stack.removeLast();
                Set<PersonId> ancestors = ancestorsByPerson.computeIfAbsent(current, ignored -> new LinkedHashSet<>());
                if (ancestors.addAll(inheritedAncestors)) {
                    stack.addAll(childrenByParent.getOrDefault(current, Set.of()));
                }
            }
        }

        private Set<PersonPair> exactParentPairs() {
            if (exactParentPairsCache != null) {
                return exactParentPairsCache;
            }
            Set<PersonPair> result = new LinkedHashSet<>();
            for (Set<PersonId> parents : parentsByChild.values()) {
                if (parents.size() == 2) {
                    result.add(pairFrom(parents));
                }
            }
            exactParentPairsCache = result;
            return exactParentPairsCache;
        }

        private Set<PersonPair> pairs(RelationRole role) {
            return pairsByRole.getOrDefault(role, Set.of());
        }

        private static ParentChildKey parentChildKey(Edge edge) {
            return switch (edge.role()) {
                case CHILD -> new ParentChildKey(edge.ownerId(), edge.targetId());
                case PARENT -> new ParentChildKey(edge.targetId(), edge.ownerId());
                case SPOUSE, SIBLING -> null;
            };
        }

        private static OptionalInt uniqueSourceCount(FactIndex facts, PersonId personId) {
            LinkedHashSet<Integer> values = new LinkedHashSet<>(facts.sourceCounts(personId, RelationRole.CHILD));
            return values.size() == 1 ? OptionalInt.of(values.getFirst()) : OptionalInt.empty();
        }

        private static PersonPair pairFrom(Set<PersonId> people) {
            var ids = people.iterator();
            return new PersonPair(ids.next(), ids.next());
        }
    }
}

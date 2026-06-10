package org.example.owner;

import org.example.facts.FactIndex;
import org.example.facts.FactIndex.RelationFact;
import org.example.model.CountFact;
import org.example.model.Gender;
import org.example.model.PersonId;
import org.example.model.RelationMention;
import org.example.model.RelationRole;
import org.example.model.RelationSubtype;
import org.example.model.SourceRecord;
import org.example.relation.RelationResolver;
import org.example.relation.TargetResolution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

public final class OwnerResolver {
    private final RelationResolver relationResolver;
    private FactIndex cachedGraphFacts;
    private Map<PersonId, List<PersonId>> cachedParentChildGraph;

    public OwnerResolver(RelationResolver relationResolver) {
        this.relationResolver = relationResolver;
    }

    public FactIndex resolve(List<SourceRecord> records) {
        FactIndex facts = FactIndex.from(records);
        attachUniqueNameRecords(facts);
        if (facts.hasPendingRecords()) {
            resolveAmbiguousNameRecords(facts);
        }
        return facts;
    }

    private void attachUniqueNameRecords(FactIndex facts) {
        applyExistingOwnerProposals(facts, uniqueNameOwnerProposals(facts), false);
    }

    private void resolveAmbiguousNameRecords(FactIndex facts) {
        applyExistingOwnerProposals(facts, ambiguousNameOwnerProposals(facts), true);
    }

    private Map<SourceRecord, OwnerProposal> uniqueNameOwnerProposals(FactIndex facts) {
        Map<SourceRecord, OwnerProposal> proposals = new LinkedHashMap<>();
        List<SourceRecord> sortedPendingRecords = facts.pendingRecordsSorted();
        for (SourceRecord record : sortedPendingRecords) {
            OwnerProposal proposal = uniqueFullNameOwner(record, facts);
            if (proposal != null) {
                proposals.put(record, proposal);
            }
        }
        return proposals;
    }

    private Map<SourceRecord, OwnerProposal> ambiguousNameOwnerProposals(FactIndex facts) {
        Map<SourceRecord, OwnerProposal> proposals = new LinkedHashMap<>();
        List<SourceRecord> sortedPendingRecords = facts.pendingRecordsSorted();
        for (SourceRecord record : sortedPendingRecords) {
            OwnerProposal proposal = ambiguousFullNameOwner(record, facts);
            if (proposal != null) {
                proposals.put(record, proposal);
            }
        }
        return proposals;
    }

    private void applyExistingOwnerProposals(
        FactIndex facts,
        Map<SourceRecord, OwnerProposal> proposals,
        boolean rejectBatchConflicts
    ) {
        boolean changed = false;
        List<SourceRecord> sortedPendingRecords = facts.pendingRecordsSorted();
        Set<SourceRecord> batchConflicts = rejectBatchConflicts
            ? batchConflictRecords(facts, proposals)
            : Set.of();
        for (SourceRecord record : sortedPendingRecords) {
            OwnerProposal proposal = proposals.get(record);
            if (proposal == null) {
                continue;
            }
            if (proposal.isRejected()) {
                discardRejectedRecord(facts, record, proposal.rejection());
                continue;
            }
            if (batchConflicts.contains(record)) {
                discardRejectedRecord(facts, record, Rejection.CONFLICTING);
                continue;
            }
            changed |= facts.attachPending(record, proposal.ownerId());
        }
        if (changed) {
            facts.rebuildIndexes();
            clearGraphCache();
        }
    }

    private void discardRejectedRecord(FactIndex facts, SourceRecord record, Rejection rejection) {
        switch (rejection) {
            case UNINFORMATIVE, UNRESOLVED, CONFLICTING -> facts.discardPendingRecord(record);
            case NONE -> throw new IllegalArgumentException("Accepted owner proposal cannot be discarded");
        }
    }

    private Set<SourceRecord> batchConflictRecords(
        FactIndex facts,
        Map<SourceRecord, OwnerProposal> proposals
    ) {
        Set<SourceRecord> conflicted = new LinkedHashSet<>();
        conflicted.addAll(batchCountConflictRecords(facts, proposals));
        conflicted.addAll(batchGenderConflictRecords(facts, proposals));
        conflicted.addAll(batchRelationRoleConflictRecords(proposals));
        conflicted.addAll(batchParentLimitConflictRecords(facts, proposals));
        conflicted.addAll(batchAncestryCycleConflictRecords(facts, proposals));
        return conflicted;
    }

    private Set<SourceRecord> batchCountConflictRecords(
        FactIndex facts,
        Map<SourceRecord, OwnerProposal> proposals
    ) {
        Map<OwnerRoleKey, Map<Integer, List<SourceRecord>>> valuesByOwnerRole = new LinkedHashMap<>();
        for (Map.Entry<SourceRecord, OwnerProposal> entry : proposals.entrySet()) {
            if (entry.getValue().isRejected()) {
                continue;
            }
            PersonId ownerId = entry.getValue().ownerId();
            for (CountFact count : entry.getKey().counts()) {
                valuesByOwnerRole
                    .computeIfAbsent(new OwnerRoleKey(ownerId, count.role()), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(count.value(), ignored -> new ArrayList<>())
                    .add(entry.getKey());
            }
        }

        Set<SourceRecord> conflicted = new LinkedHashSet<>();
        for (Map.Entry<OwnerRoleKey, Map<Integer, List<SourceRecord>>> entry : valuesByOwnerRole.entrySet()) {
            List<Integer> committedValues = facts.sourceCounts(entry.getKey().ownerId(), entry.getKey().role());
            if (committedValues.isEmpty() && entry.getValue().size() > 1) {
                entry.getValue().values().forEach(conflicted::addAll);
            } else if (!committedValues.isEmpty()) {
                for (Map.Entry<Integer, List<SourceRecord>> proposed : entry.getValue().entrySet()) {
                    if (!committedValues.contains(proposed.getKey())) {
                        conflicted.addAll(proposed.getValue());
                    }
                }
            }
        }
        return conflicted;
    }

    private Set<SourceRecord> batchGenderConflictRecords(
        FactIndex facts,
        Map<SourceRecord, OwnerProposal> proposals
    ) {
        Map<PersonId, Map<Gender, List<SourceRecord>>> gendersByPerson = new LinkedHashMap<>();
        for (Map.Entry<SourceRecord, OwnerProposal> entry : proposals.entrySet()) {
            if (entry.getValue().isRejected()) {
                continue;
            }
            PersonId ownerId = entry.getValue().ownerId();
            for (Gender gender : ownerGenderFacts(entry.getKey())) {
                addGenderProposal(gendersByPerson, ownerId, gender, entry.getKey());
            }
            for (RelationMention relation : entry.getKey().relations()) {
                if (relation.targetId() != null && relation.subtype().targetGenderRequirement() != Gender.UNKNOWN) {
                    addGenderProposal(gendersByPerson, relation.targetId(), relation.subtype().targetGenderRequirement(), entry.getKey());
                }
            }
        }

        Set<SourceRecord> conflicted = new LinkedHashSet<>();
        for (Map.Entry<PersonId, Map<Gender, List<SourceRecord>>> entry : gendersByPerson.entrySet()) {
            LinkedHashSet<Gender> committed = facts.genderFacts(entry.getKey());
            if (committed.isEmpty() && entry.getValue().size() > 1) {
                entry.getValue().values().forEach(conflicted::addAll);
            } else if (!committed.isEmpty()) {
                for (Map.Entry<Gender, List<SourceRecord>> proposed : entry.getValue().entrySet()) {
                    if (!committed.contains(proposed.getKey())) {
                        conflicted.addAll(proposed.getValue());
                    }
                }
            }
        }
        return conflicted;
    }

    private void addGenderProposal(
        Map<PersonId, Map<Gender, List<SourceRecord>>> gendersByPerson,
        PersonId personId,
        Gender gender,
        SourceRecord record
    ) {
        if (gender == Gender.UNKNOWN) {
            return;
        }
        gendersByPerson
            .computeIfAbsent(personId, ignored -> new LinkedHashMap<>())
            .computeIfAbsent(gender, ignored -> new ArrayList<>())
            .add(record);
    }

    private Set<SourceRecord> batchRelationRoleConflictRecords(Map<SourceRecord, OwnerProposal> proposals) {
        Map<OwnerTargetKey, Map<RelationRole, List<SourceRecord>>> rolesByOwnerTarget = new LinkedHashMap<>();
        for (Map.Entry<SourceRecord, OwnerProposal> entry : proposals.entrySet()) {
            if (entry.getValue().isRejected()) {
                continue;
            }
            PersonId ownerId = entry.getValue().ownerId();
            for (RelationMention relation : entry.getKey().relations()) {
                if (relation.targetId() == null) {
                    continue;
                }
                rolesByOwnerTarget
                    .computeIfAbsent(new OwnerTargetKey(ownerId, relation.targetId()), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(relation.role(), ignored -> new ArrayList<>())
                    .add(entry.getKey());
            }
        }

        Set<SourceRecord> conflicted = new LinkedHashSet<>();
        for (Map<RelationRole, List<SourceRecord>> proposedRoles : rolesByOwnerTarget.values()) {
            if (proposedRoles.size() > 1) {
                proposedRoles.values().forEach(conflicted::addAll);
            }
        }
        for (Map.Entry<OwnerTargetKey, Map<RelationRole, List<SourceRecord>>> entry : rolesByOwnerTarget.entrySet()) {
            Map<RelationRole, List<SourceRecord>> reverseRoles =
                rolesByOwnerTarget.get(new OwnerTargetKey(entry.getKey().targetId(), entry.getKey().ownerId()));
            if (reverseRoles == null) {
                continue;
            }
            for (Map.Entry<RelationRole, List<SourceRecord>> role : entry.getValue().entrySet()) {
                for (Map.Entry<RelationRole, List<SourceRecord>> reverseRole : reverseRoles.entrySet()) {
                    if (reverseRole.getKey() != role.getKey().reciprocal()) {
                        conflicted.addAll(role.getValue());
                        conflicted.addAll(reverseRole.getValue());
                    }
                }
            }
        }
        return conflicted;
    }

    private Set<SourceRecord> batchParentLimitConflictRecords(
        FactIndex facts,
        Map<SourceRecord, OwnerProposal> proposals
    ) {
        Map<PersonId, Map<PersonId, List<SourceRecord>>> parentsByChild = new LinkedHashMap<>();
        for (Map.Entry<SourceRecord, OwnerProposal> entry : proposals.entrySet()) {
            if (entry.getValue().isRejected()) {
                continue;
            }
            PersonId ownerId = entry.getValue().ownerId();
            for (RelationMention relation : entry.getKey().relations()) {
                ParentChildKey key = parentChildKey(ownerId, relation);
                if (key == null) {
                    continue;
                }
                parentsByChild
                    .computeIfAbsent(key.childId(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(key.parentId(), ignored -> new ArrayList<>())
                    .add(entry.getKey());
            }
        }

        Set<SourceRecord> conflicted = new LinkedHashSet<>();
        for (Map.Entry<PersonId, Map<PersonId, List<SourceRecord>>> entry : parentsByChild.entrySet()) {
            LinkedHashSet<PersonId> committedParents =
                new LinkedHashSet<>(facts.parentsByChild().getOrDefault(entry.getKey(), new LinkedHashSet<>()));
            LinkedHashSet<PersonId> allParents = new LinkedHashSet<>(committedParents);
            allParents.addAll(entry.getValue().keySet());
            if (allParents.size() <= 2) {
                continue;
            }
            for (Map.Entry<PersonId, List<SourceRecord>> proposed : entry.getValue().entrySet()) {
                if (!committedParents.contains(proposed.getKey())) {
                    conflicted.addAll(proposed.getValue());
                }
            }
        }
        return conflicted;
    }

    private Set<SourceRecord> batchAncestryCycleConflictRecords(
        FactIndex facts,
        Map<SourceRecord, OwnerProposal> proposals
    ) {
        Map<PersonId, List<PersonId>> graph = copyGraph(parentChildGraph(facts));
        Map<ParentChildKey, List<SourceRecord>> proposedParentEdges = new LinkedHashMap<>();
        Map<OwnerTargetKey, List<SourceRecord>> proposedPeerEdges = new LinkedHashMap<>();
        for (Map.Entry<SourceRecord, OwnerProposal> entry : proposals.entrySet()) {
            if (entry.getValue().isRejected()) {
                continue;
            }
            PersonId ownerId = entry.getValue().ownerId();
            for (RelationMention relation : entry.getKey().relations()) {
                ParentChildKey parentChild = parentChildKey(ownerId, relation);
                if (parentChild != null) {
                    proposedParentEdges
                        .computeIfAbsent(parentChild, ignored -> new ArrayList<>())
                        .add(entry.getKey());
                }
                if (isPeerRole(relation.role()) && relation.targetId() != null) {
                    proposedPeerEdges
                        .computeIfAbsent(new OwnerTargetKey(ownerId, relation.targetId()), ignored -> new ArrayList<>())
                        .add(entry.getKey());
                }
            }
        }

        proposedParentEdges.keySet().forEach(edge -> addParentChild(graph, edge.parentId(), edge.childId()));
        Set<SourceRecord> conflicted = new LinkedHashSet<>();
        for (Map.Entry<ParentChildKey, List<SourceRecord>> entry : proposedParentEdges.entrySet()) {
            ParentChildKey edge = entry.getKey();
            if (reaches(graph, edge.childId(), edge.parentId())) {
                conflicted.addAll(entry.getValue());
            }
        }
        for (Map.Entry<OwnerTargetKey, List<SourceRecord>> entry : proposedPeerEdges.entrySet()) {
            OwnerTargetKey edge = entry.getKey();
            if (reaches(graph, edge.ownerId(), edge.targetId()) || reaches(graph, edge.targetId(), edge.ownerId())) {
                conflicted.addAll(entry.getValue());
            }
        }
        return conflicted;
    }

    private OwnerProposal uniqueFullNameOwner(SourceRecord record, FactIndex facts) {
        if (!record.ownerIds().isEmpty() || record.name() == null) {
            return null;
        }
        if (!record.name().isFull()) {
            return OwnerProposal.unresolved();
        }

        List<PersonId> candidates = facts.personIdsByName(record.name());
        if (candidates.size() != 1) {
            return null;
        }
        PersonId candidate = candidates.getFirst();
        return OwnerProposal.attach(candidate);
    }

    private OwnerProposal ambiguousFullNameOwner(SourceRecord record, FactIndex facts) {
        if (!record.ownerIds().isEmpty() || record.name() == null || !record.name().isFull()) {
            return null;
        }
        List<PersonId> candidates = facts.personIdsByName(record.name());
        if (candidates.size() < 2) {
            return null;
        }
        if (!hasFactsBeyondName(record)) {
            return OwnerProposal.uninformative();
        }
        return ambiguousFullNameDecision(record, facts, candidates);
    }

    private OwnerProposal ambiguousFullNameDecision(
        SourceRecord record,
        FactIndex facts,
        List<PersonId> candidates
    ) {
        List<RelationMention> byIdRelations = record.relations().stream()
            .filter(relation -> relation.targetId() != null)
            .toList();
        LinkedHashSet<PersonId> remaining = new LinkedHashSet<>(candidates);
        boolean directTargetExcluded = false;
        LinkedHashSet<PersonId> supportedCandidates = new LinkedHashSet<>();
        LinkedHashSet<PersonId> contradicted = new LinkedHashSet<>();

        for (RelationMention relation : byIdRelations) {
            if (remaining.remove(relation.targetId())) {
                directTargetExcluded = true;
            }
        }

        for (PersonId candidate : remaining) {
            for (RelationMention relation : record.relations()) {
                if (relation.targetName() == null || !relation.targetName().isFull()) {
                    continue;
                }
                TargetResolution resolution = relationResolver.resolveTarget(candidate, relation, facts);
                if (resolution.rejectedConflict()) {
                    contradicted.add(candidate);
                    continue;
                }
                if (resolution.hasPositiveSupport()) {
                    supportedCandidates.add(candidate);
                }
            }
        }

        PersonId directExclusionSelection = remaining.size() == 1 && directTargetExcluded
            ? remaining.getFirst()
            : null;

        for (PersonId candidate : remaining) {
            for (RelationMention relation : byIdRelations) {
                if (relationResolver.hasMatchingRelationFact(facts, candidate, relation)) {
                    supportedCandidates.add(candidate);
                }
            }
        }
        addSharedChildSpouseSupport(record, facts, remaining, supportedCandidates);
        addCountSupport(record, facts, remaining, supportedCandidates, contradicted);
        for (PersonId candidate : remaining) {
            if (hasHardConflict(record, candidate, facts)
                || hasProjectedByNameRelationConflict(record, candidate, facts)) {
                contradicted.add(candidate);
            }
        }

        remaining.removeAll(contradicted);
        supportedCandidates.removeIf(candidate -> !remaining.contains(candidate));

        if (supportedCandidates.size() == 1) {
            return OwnerProposal.attach(supportedCandidates.getFirst());
        }
        if (directExclusionSelection != null && remaining.contains(directExclusionSelection)) {
            return OwnerProposal.attach(directExclusionSelection);
        }
        return OwnerProposal.unresolved();
    }

    private boolean hasFactsBeyondName(SourceRecord record) {
        return record.gender() != Gender.UNKNOWN
            || !record.relations().isEmpty()
            || !record.counts().isEmpty();
    }

    private void addSharedChildSpouseSupport(
        SourceRecord record,
        FactIndex facts,
        LinkedHashSet<PersonId> remaining,
        LinkedHashSet<PersonId> supportedCandidates
    ) {
        for (RelationMention relation : record.relations()) {
            if (relation.role() != RelationRole.SPOUSE || relation.targetId() == null) {
                continue;
            }
            PersonId targetId = relation.targetId();
            var targetChildren = facts.childIds(targetId);
            if (targetChildren.isEmpty()) {
                continue;
            }
            LinkedHashSet<PersonId> sharedChildCandidates = new LinkedHashSet<>();
            for (PersonId candidate : remaining) {
                for (PersonId childId : facts.childIds(candidate)) {
                    if (targetChildren.contains(childId)) {
                        sharedChildCandidates.add(candidate);
                    }
                }
            }
            if (sharedChildCandidates.size() == 1) {
                supportedCandidates.add(sharedChildCandidates.getFirst());
            }
        }
    }

    private void addCountSupport(
        SourceRecord record,
        FactIndex facts,
        LinkedHashSet<PersonId> remaining,
        LinkedHashSet<PersonId> supportedCandidates,
        LinkedHashSet<PersonId> contradicted
    ) {
        for (CountFact count : stableRecordCounts(record)) {
            List<PersonId> matchingCandidates = new ArrayList<>();
            Map<PersonId, Integer> mismatchingCandidates = new LinkedHashMap<>();
            for (PersonId candidate : remaining) {
                Integer candidateValue = candidateCountValue(facts, candidate, count.role());
                if (candidateValue == null) {
                    continue;
                }
                if (candidateValue == count.value()) {
                    matchingCandidates.add(candidate);
                } else {
                    mismatchingCandidates.put(candidate, candidateValue);
                }
            }
            if (matchingCandidates.size() == 1) {
                PersonId matched = matchingCandidates.getFirst();
                supportedCandidates.add(matched);
                contradicted.addAll(mismatchingCandidates.keySet());
            } else if (matchingCandidates.isEmpty()) {
                contradicted.addAll(mismatchingCandidates.keySet());
            }
        }
    }

    private boolean hasHardConflict(SourceRecord record, PersonId candidate, FactIndex facts) {
        if (record.relations().stream().anyMatch(relation -> candidate.equals(relation.targetId()))) {
            return true;
        }
        for (Gender gender : ownerGenderFacts(record)) {
            if (genderConflict(gender, facts.genderFacts(candidate))) {
                return true;
            }
        }
        for (CountFact count : record.counts()) {
            List<Integer> existingValues = facts.sourceCounts(candidate, count.role());
            if (!existingValues.isEmpty() && !existingValues.contains(count.value())) {
                return true;
            }
        }
        for (RelationMention relation : record.relations()) {
            if (relation.targetId() == null) {
                continue;
            }
            PersonId targetId = relation.targetId();
            if (candidate.equals(targetId)) {
                return true;
            }
            RelationSubtype subtype = effectiveSubtype(
                relation.role(),
                relation.subtype(),
                targetId,
                facts,
                candidate,
                record
            );
            if (genderConflict(subtype.ownerGenderRequirement(), projectedGenderFacts(candidate, facts, candidate, record))) {
                return true;
            }
            if (genderConflict(subtype.targetGenderRequirement(), projectedGenderFacts(targetId, facts, candidate, record))) {
                return true;
            }
            if (directRelationConflict(relation, candidate, targetId, facts)) {
                return true;
            }
            if (ancestryConflict(relation, candidate, targetId, facts)) {
                return true;
            }
            if (spouseChildCountConflict(record, candidate, relation.role(), targetId, facts)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasProjectedByNameRelationConflict(SourceRecord record, PersonId candidate, FactIndex facts) {
        for (RelationMention relation : record.relations()) {
            if (relation.targetName() == null || !relation.targetName().isFull()) {
                continue;
            }
            TargetResolution resolution = relationResolver.resolveTarget(candidate, relation, facts);
            if (resolution.rejectedConflict()) {
                return true;
            }
            if (resolution.selectedTargetId() == null) {
                continue;
            }
            if (hasProjectedRelationConflict(record, candidate, relation, resolution.selectedTargetId(), facts)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasProjectedRelationConflict(
        SourceRecord record,
        PersonId candidate,
        RelationMention relation,
        PersonId targetId,
        FactIndex facts
    ) {
        if (candidate.equals(targetId)) {
            return true;
        }
        RelationSubtype subtype = effectiveSubtype(
            relation.role(),
            relation.subtype(),
            targetId,
            facts,
            candidate,
            record
        );
        return genderConflict(subtype.ownerGenderRequirement(), projectedGenderFacts(candidate, facts, candidate, record))
            || genderConflict(subtype.targetGenderRequirement(), projectedGenderFacts(targetId, facts, candidate, record))
            || relationResolver.hasResolvedRelationConflict(candidate, relation, targetId, facts)
            || ancestryConflict(relation, candidate, targetId, facts)
            || spouseChildCountConflict(record, candidate, relation.role(), targetId, facts);
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

    private boolean ancestryConflict(
        RelationMention relation,
        PersonId candidate,
        PersonId targetId,
        FactIndex facts
    ) {
        Map<PersonId, List<PersonId>> graph = parentChildGraph(facts);
        if (isPeerRole(relation.role()) && (reaches(graph, candidate, targetId) || reaches(graph, targetId, candidate))) {
            return true;
        }
        ParentChildKey parentChild = parentChildKey(candidate, relation.role(), targetId);
        if (parentChild == null) {
            return false;
        }
        LinkedHashSet<PersonId> committedParents =
            new LinkedHashSet<>(facts.parentsByChild().getOrDefault(parentChild.childId(), new LinkedHashSet<>()));
        if (!committedParents.contains(parentChild.parentId()) && committedParents.size() >= 2) {
            return true;
        }
        return reaches(graph, parentChild.childId(), parentChild.parentId());
    }

    private List<Gender> ownerGenderFacts(SourceRecord record) {
        List<Gender> facts = new ArrayList<>();
        if (record.gender() != Gender.UNKNOWN) {
            facts.add(record.gender());
        }
        for (RelationMention relation : record.relations()) {
            if (relation.subtype().ownerGenderRequirement() != Gender.UNKNOWN) {
                facts.add(relation.subtype().ownerGenderRequirement());
            }
        }
        return facts;
    }

    private LinkedHashSet<Gender> projectedGenderFacts(
        PersonId personId,
        FactIndex facts,
        PersonId projectedOwner,
        SourceRecord record
    ) {
        LinkedHashSet<Gender> result = new LinkedHashSet<>(facts.genderFacts(personId));
        if (personId.equals(projectedOwner)) {
            if (record.gender() != Gender.UNKNOWN) {
                result.add(record.gender());
            }
            for (RelationMention relation : record.relations()) {
                if (relation.subtype().ownerGenderRequirement() != Gender.UNKNOWN) {
                    result.add(relation.subtype().ownerGenderRequirement());
                }
            }
        }
        for (RelationMention relation : record.relations()) {
            if (personId.equals(relation.targetId())
                && relation.subtype().targetGenderRequirement() != Gender.UNKNOWN) {
                result.add(relation.subtype().targetGenderRequirement());
            }
        }
        return result;
    }

    private RelationSubtype effectiveSubtype(
        RelationRole role,
        RelationSubtype subtype,
        PersonId targetId,
        FactIndex facts,
        PersonId projectedOwner,
        SourceRecord record
    ) {
        return RelationResolver.restoreSubtype(
            role,
            subtype,
            resolvedGender(projectedGenderFacts(targetId, facts, projectedOwner, record))
        );
    }

    private Gender resolvedGender(LinkedHashSet<Gender> genders) {
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

    private ParentChildKey parentChildKey(PersonId ownerId, RelationMention relation) {
        if (relation.targetId() == null) {
            return null;
        }
        return parentChildKey(ownerId, relation.role(), relation.targetId());
    }

    private ParentChildKey parentChildKey(PersonId ownerId, RelationRole role, PersonId targetId) {
        return switch (role) {
            case CHILD -> new ParentChildKey(ownerId, targetId);
            case PARENT -> new ParentChildKey(targetId, ownerId);
            case SPOUSE, SIBLING -> null;
        };
    }

    private boolean spouseChildCountConflict(
        SourceRecord record,
        PersonId projectedOwner,
        RelationRole role,
        PersonId targetId,
        FactIndex facts
    ) {
        if (role == RelationRole.SPOUSE) {
            return spouseChildCountConflict(record, projectedOwner, projectedOwner, targetId, null, facts);
        }
        ParentChildKey parentChild = parentChildKey(projectedOwner, role, targetId);
        if (parentChild == null) {
            return false;
        }
        for (PersonId spouse : facts.spouses(parentChild.parentId())) {
            if (spouseChildCountConflict(record, projectedOwner, parentChild.parentId(), spouse, parentChild, facts)) {
                return true;
            }
        }
        return false;
    }

    private boolean spouseChildCountConflict(
        SourceRecord record,
        PersonId projectedOwner,
        PersonId left,
        PersonId right,
        ParentChildKey proposedParentChild,
        FactIndex facts
    ) {
        OptionalInt leftCount = sourceChildCount(record, projectedOwner, left, facts);
        OptionalInt rightCount = sourceChildCount(record, projectedOwner, right, facts);
        if (leftCount.isEmpty() || rightCount.isEmpty()) {
            return false;
        }
        if (leftCount.orElseThrow() != rightCount.orElseThrow()) {
            return true;
        }

        int count = leftCount.orElseThrow();
        Set<PersonId> leftChildren = childIdsWithProposed(facts, left, proposedParentChild);
        Set<PersonId> rightChildren = childIdsWithProposed(facts, right, proposedParentChild);
        return leftChildren.size() == count
            && rightChildren.size() == count
            && !leftChildren.equals(rightChildren);
    }

    private OptionalInt sourceChildCount(
        SourceRecord record,
        PersonId projectedOwner,
        PersonId personId,
        FactIndex facts
    ) {
        LinkedHashSet<Integer> values = new LinkedHashSet<>(facts.sourceCounts(personId, RelationRole.CHILD));
        if (personId.equals(projectedOwner)) {
            for (CountFact count : record.counts()) {
                if (count.role() == RelationRole.CHILD) {
                    values.add(count.value());
                }
            }
        }
        return values.size() == 1 ? OptionalInt.of(values.getFirst()) : OptionalInt.empty();
    }

    private Set<PersonId> childIdsWithProposed(
        FactIndex facts,
        PersonId parentId,
        ParentChildKey proposedParentChild
    ) {
        Set<PersonId> children = facts.childIds(parentId);
        if (proposedParentChild != null && proposedParentChild.parentId().equals(parentId)) {
            children.add(proposedParentChild.childId());
        }
        return children;
    }

    private boolean isPeerRole(RelationRole role) {
        return role == RelationRole.SPOUSE || role == RelationRole.SIBLING;
    }

    private Map<PersonId, List<PersonId>> parentChildGraph(FactIndex facts) {
        if (cachedGraphFacts == facts && cachedParentChildGraph != null) {
            return cachedParentChildGraph;
        }
        Map<PersonId, List<PersonId>> graph = new LinkedHashMap<>();
        for (var person : facts.people()) {
            for (RelationFact fact : facts.relationsFrom(person.personId())) {
                switch (fact.role()) {
                    case CHILD -> addParentChild(graph, fact.ownerId(), fact.targetId());
                    case PARENT -> addParentChild(graph, fact.targetId(), fact.ownerId());
                    case SPOUSE, SIBLING -> {
                    }
                }
            }
        }
        cachedGraphFacts = facts;
        cachedParentChildGraph = graph;
        return graph;
    }

    private Map<PersonId, List<PersonId>> copyGraph(Map<PersonId, List<PersonId>> graph) {
        Map<PersonId, List<PersonId>> copy = new LinkedHashMap<>();
        for (Map.Entry<PersonId, List<PersonId>> entry : graph.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    private void clearGraphCache() {
        cachedGraphFacts = null;
        cachedParentChildGraph = null;
    }

    private void addParentChild(Map<PersonId, List<PersonId>> graph, PersonId parentId, PersonId childId) {
        graph.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(childId);
    }

    private boolean reaches(Map<PersonId, List<PersonId>> graph, PersonId start, PersonId target) {
        List<PersonId> stack = new ArrayList<>();
        Set<PersonId> visited = new LinkedHashSet<>();
        stack.add(start);
        while (!stack.isEmpty()) {
            PersonId current = stack.removeLast();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(target)) {
                return true;
            }
            stack.addAll(graph.getOrDefault(current, List.of()));
        }
        return false;
    }

    private List<CountFact> stableRecordCounts(SourceRecord record) {
        Map<RelationRole, LinkedHashSet<Integer>> valuesByRole = new LinkedHashMap<>();
        Map<RelationRole, CountFact> firstCountByRole = new LinkedHashMap<>();
        for (CountFact count : record.counts()) {
            valuesByRole.computeIfAbsent(count.role(), ignored -> new LinkedHashSet<>()).add(count.value());
            firstCountByRole.putIfAbsent(count.role(), count);
        }

        List<CountFact> stableCounts = new ArrayList<>();
        for (Map.Entry<RelationRole, LinkedHashSet<Integer>> entry : valuesByRole.entrySet()) {
            if (entry.getValue().size() == 1) {
                stableCounts.add(firstCountByRole.get(entry.getKey()));
            }
        }
        return stableCounts;
    }

    private Integer candidateCountValue(FactIndex facts, PersonId candidate, RelationRole role) {
        List<Integer> sourceValues = facts.sourceCounts(candidate, role);
        LinkedHashSet<Integer> distinctSourceValues = new LinkedHashSet<>(sourceValues);
        if (distinctSourceValues.size() > 1) {
            return null;
        }
        if (distinctSourceValues.size() == 1) {
            return distinctSourceValues.getFirst();
        }
        return facts.normalizedRelationCounts(candidate).getOrDefault(role, 0);
    }

    private record OwnerRoleKey(PersonId ownerId, RelationRole role) {}

    private record OwnerTargetKey(PersonId ownerId, PersonId targetId) {}

    private record ParentChildKey(PersonId parentId, PersonId childId) {}

    private record OwnerProposal(PersonId ownerId, Rejection rejection) {
        private static OwnerProposal attach(PersonId ownerId) {
            return new OwnerProposal(ownerId, Rejection.NONE);
        }

        private static OwnerProposal unresolved() {
            return new OwnerProposal(null, Rejection.UNRESOLVED);
        }

        private static OwnerProposal uninformative() {
            return new OwnerProposal(null, Rejection.UNINFORMATIVE);
        }

        private boolean isRejected() {
            return rejection != Rejection.NONE;
        }
    }

    private enum Rejection {
        NONE,
        UNINFORMATIVE,
        UNRESOLVED,
        CONFLICTING
    }
}

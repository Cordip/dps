package org.example.validation;

import org.example.model.Gender;
import org.example.model.RelationRole;
import org.example.model.RelationSubtype;
import org.example.structured.StructuredCount;
import org.example.structured.StructuredPeopleDocument;
import org.example.structured.StructuredPerson;
import org.example.structured.StructuredRelationRef;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

public final class OutputHardRuleValidator {
    public boolean isValid(StructuredPeopleDocument document) {
        return isValid(toDocument(document));
    }

    private boolean isValid(ValidationDocument document) {
        if (!canonicalPeople(document)) {
            return false;
        }
        Graph graph = Graph.from(document.people());
        return relationsValid(document.people(), graph)
            && parentGraphValid(graph)
            && crossRoleConflictsAbsent(graph)
            && spouseChildCountRulesValid(graph)
            && siblingParentRulesValid(graph)
            && coparentSpouseCompatible(graph);
    }

    private ValidationDocument toDocument(StructuredPeopleDocument document) {
        List<VPerson> people = document.people().stream()
            .map(person -> new VPerson(
                person.id(),
                genderValue(person.gender()),
                nameValid(person),
                person.relations().all().stream()
                    .map(relation -> new VRelation(relation.role(), relation.subtype(), relation.targetId()))
                    .toList(),
                counts(person)
            ))
            .toList();
        return new ValidationDocument(document.count(), people);
    }

    private List<VCount> counts(StructuredPerson person) {
        return List.of(
            count(RelationRole.SPOUSE, person.counts().spouses()),
            count(RelationRole.PARENT, person.counts().parents()),
            count(RelationRole.SIBLING, person.counts().siblings()),
            count(RelationRole.CHILD, person.counts().children())
        );
    }

    private VCount count(RelationRole role, StructuredCount count) {
        return new VCount(
            role,
            count.source() == null ? OptionalInt.empty() : OptionalInt.of(count.source()),
            count.inferred(),
            count.validation()
        );
    }

    private Gender genderValue(org.example.structured.StructuredGender gender) {
        if (gender == null) {
            return Gender.UNKNOWN;
        }
        return switch (gender.value()) {
            case "male" -> Gender.MALE;
            case "female" -> Gender.FEMALE;
            default -> Gender.UNKNOWN;
        };
    }

    private boolean nameValid(StructuredPerson person) {
        if (person.name() == null) {
            return true;
        }
        return !collapseXmlToken(person.name().first()).isBlank()
            && !collapseXmlToken(person.name().last()).isBlank();
    }

    private String collapseXmlToken(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private boolean canonicalPeople(ValidationDocument document) {
        if (document.count() != document.people().size()) {
            return false;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (VPerson person : document.people()) {
            if (!seen.add(person.id()) || !person.id().matches("P[1-9][0-9]*") || !person.nameValid()) {
                return false;
            }
            for (VCount count : person.counts()) {
                if (count.inferred() < 0
                    || (count.source().isPresent() && count.source().orElseThrow() < 0)
                    || "ambiguous-source".equals(count.validation())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean relationsValid(List<VPerson> people, Graph graph) {
        for (VPerson owner : people) {
            Map<String, Set<RelationRole>> rolesByTarget = new LinkedHashMap<>();
            Set<RelationKey> relationKeys = new LinkedHashSet<>();
            for (VRelation relation : owner.relations()) {
                VPerson target = graph.person(relation.targetId());
                if (target == null
                    || relation.targetId().equals(owner.id())
                    || !relationKeys.add(new RelationKey(relation.role(), relation.targetId()))
                    || !rolesByTarget.computeIfAbsent(relation.targetId(), ignored -> EnumSet.noneOf(RelationRole.class))
                        .add(relation.role())
                    || rolesByTarget.get(relation.targetId()).size() > 1
                    || !subtypeGenderValid(owner, relation, target)
                    || (relation.subtype() == RelationSubtype.GENERIC && target.gender() != Gender.UNKNOWN)
                    || !reciprocalCompatible(owner.id(), relation, target)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean subtypeGenderValid(VPerson owner, VRelation relation, VPerson target) {
        return genderAllows(owner.gender(), relation.subtype().ownerGenderRequirement())
            && genderAllows(target.gender(), relation.subtype().targetGenderRequirement());
    }

    private boolean genderAllows(Gender actual, Gender required) {
        return required == Gender.UNKNOWN || actual == Gender.UNKNOWN || actual == required;
    }

    private boolean reciprocalCompatible(String ownerId, VRelation relation, VPerson target) {
        boolean hasReverse = false;
        for (VRelation reverse : target.relations()) {
            if (!reverse.targetId().equals(ownerId)) {
                continue;
            }
            hasReverse = true;
            if (reverse.role() == relation.role().reciprocal()
                && relation.subtype().reverseCompatible(reverse.subtype())) {
                return true;
            }
        }
        return !hasReverse;
    }

    private boolean parentGraphValid(Graph graph) {
        for (Map.Entry<String, Set<String>> entry : graph.parentsByChild().entrySet()) {
            if (entry.getValue().size() > 2) {
                return false;
            }
            for (String parent : entry.getValue()) {
                if (hasParentPath(parent, entry.getKey(), graph)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean crossRoleConflictsAbsent(Graph graph) {
        Set<PersonPair> spousePairs = graph.pairs(RelationRole.SPOUSE);
        Set<PersonPair> siblingPairs = graph.pairs(RelationRole.SIBLING);
        Set<PersonPair> parentChildPairs = new LinkedHashSet<>(graph.pairs(RelationRole.PARENT));
        parentChildPairs.addAll(graph.pairs(RelationRole.CHILD));

        for (PersonPair pair : spousePairs) {
            if (siblingPairs.contains(pair) || ancestryRelated(pair, graph)) {
                return false;
            }
        }
        for (PersonPair pair : siblingPairs) {
            if (parentChildPairs.contains(pair) || ancestryRelated(pair, graph)) {
                return false;
            }
        }
        for (Set<String> parents : graph.parentsByChild().values()) {
            if (parents.size() == 2 && siblingPairs.contains(pairFrom(parents))) {
                return false;
            }
        }
        return true;
    }

    private boolean ancestryRelated(PersonPair pair, Graph graph) {
        return hasParentPath(pair.left(), pair.right(), graph) || hasParentPath(pair.right(), pair.left(), graph);
    }

    private boolean hasParentPath(String start, String target, Graph graph) {
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            for (String parent : graph.parentsByChild().getOrDefault(current, Set.of())) {
                if (parent.equals(target)) {
                    return true;
                }
                queue.addLast(parent);
            }
        }
        return false;
    }

    private boolean spouseChildCountRulesValid(Graph graph) {
        for (PersonPair spousePair : graph.pairs(RelationRole.SPOUSE)) {
            OptionalInt leftCount = sourceCount(graph.person(spousePair.left()), RelationRole.CHILD);
            OptionalInt rightCount = sourceCount(graph.person(spousePair.right()), RelationRole.CHILD);
            if (leftCount.isEmpty() || rightCount.isEmpty()) {
                continue;
            }
            if (leftCount.orElseThrow() != rightCount.orElseThrow()) {
                return false;
            }

            Set<String> leftChildren = graph.childrenByParent().getOrDefault(spousePair.left(), Set.of());
            Set<String> rightChildren = graph.childrenByParent().getOrDefault(spousePair.right(), Set.of());
            if (leftChildren.size() == leftCount.orElseThrow()
                && rightChildren.size() == rightCount.orElseThrow()
                && !leftChildren.equals(rightChildren)) {
                return false;
            }
        }
        return true;
    }

    private OptionalInt sourceCount(VPerson person, RelationRole role) {
        for (VCount count : person.counts()) {
            if (count.role() == role) {
                return count.source();
            }
        }
        return OptionalInt.empty();
    }

    private boolean siblingParentRulesValid(Graph graph) {
        if (!siblingComponentParentsValid(graph)) {
            return false;
        }

        Set<PersonPair> spousePairs = graph.pairs(RelationRole.SPOUSE);
        Set<PersonPair> exactParentPairs = exactParentPairs(graph.parentsByChild());
        for (PersonPair siblingPair : graph.pairs(RelationRole.SIBLING)) {
            Set<String> leftParents = graph.parentsByChild().getOrDefault(siblingPair.left(), Set.of());
            Set<String> rightParents = graph.parentsByChild().getOrDefault(siblingPair.right(), Set.of());
            if (leftParents.size() == 1 && rightParents.size() == 1) {
                String leftParent = leftParents.iterator().next();
                String rightParent = rightParents.iterator().next();
                if (!leftParent.equals(rightParent)
                    && !spousePairs.contains(new PersonPair(leftParent, rightParent))
                    && !exactParentPairs.contains(new PersonPair(leftParent, rightParent))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean siblingComponentParentsValid(Graph graph) {
        Map<String, Set<String>> siblings = new LinkedHashMap<>();
        for (PersonPair pair : graph.pairs(RelationRole.SIBLING)) {
            siblings.computeIfAbsent(pair.left(), ignored -> new LinkedHashSet<>()).add(pair.right());
            siblings.computeIfAbsent(pair.right(), ignored -> new LinkedHashSet<>()).add(pair.left());
        }

        Set<String> seen = new LinkedHashSet<>();
        for (String start : siblings.keySet()) {
            Set<String> parentUnion = new LinkedHashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String current = queue.removeFirst();
                if (seen.add(current)) {
                    parentUnion.addAll(graph.parentsByChild().getOrDefault(current, Set.of()));
                    siblings.getOrDefault(current, Set.of()).forEach(queue::addLast);
                }
            }
            if (parentUnion.size() > 2) {
                return false;
            }
        }
        return true;
    }

    private boolean coparentSpouseCompatible(Graph graph) {
        for (PersonPair parentPair : exactParentPairs(graph.parentsByChild())) {
            Set<String> leftSpouses = graph.spousesByPerson().getOrDefault(parentPair.left(), Set.of());
            Set<String> rightSpouses = graph.spousesByPerson().getOrDefault(parentPair.right(), Set.of());
            if ((!leftSpouses.isEmpty() && !leftSpouses.equals(Set.of(parentPair.right())))
                || (!rightSpouses.isEmpty() && !rightSpouses.equals(Set.of(parentPair.left())))) {
                return false;
            }
        }
        return true;
    }

    private Set<PersonPair> exactParentPairs(Map<String, Set<String>> parentsByChild) {
        Set<PersonPair> result = new LinkedHashSet<>();
        for (Set<String> parents : parentsByChild.values()) {
            if (parents.size() == 2) {
                result.add(pairFrom(parents));
            }
        }
        return result;
    }

    private PersonPair pairFrom(Set<String> people) {
        var ids = people.iterator();
        return new PersonPair(ids.next(), ids.next());
    }

    private record ValidationDocument(int count, List<VPerson> people) {}

    private record VPerson(String id, Gender gender, boolean nameValid, List<VRelation> relations, List<VCount> counts) {}

    private record VRelation(RelationRole role, RelationSubtype subtype, String targetId) {}

    private record VCount(RelationRole role, OptionalInt source, int inferred, String validation) {}

    private record RelationKey(RelationRole role, String targetId) {}

    private record PersonPair(String left, String right) {
        private PersonPair {
            if (left.compareTo(right) > 0) {
                String previousLeft = left;
                left = right;
                right = previousLeft;
            }
        }
    }

    private record Graph(
        Map<String, VPerson> peopleById,
        Map<String, Set<String>> parentsByChild,
        Map<String, Set<String>> childrenByParent,
        Map<RelationRole, Set<PersonPair>> pairsByRole,
        Map<String, Set<String>> spousesByPerson
    ) {
        private static Graph from(List<VPerson> people) {
            Map<String, VPerson> peopleById = new LinkedHashMap<>();
            Map<String, Set<String>> parentsByChild = new LinkedHashMap<>();
            Map<String, Set<String>> childrenByParent = new LinkedHashMap<>();
            Map<RelationRole, Set<PersonPair>> pairsByRole = new EnumMap<>(RelationRole.class);
            Map<String, Set<String>> spousesByPerson = new LinkedHashMap<>();

            for (VPerson person : people) {
                String ownerId = person.id();
                peopleById.put(ownerId, person);
                for (VRelation relation : person.relations()) {
                    pairsByRole
                        .computeIfAbsent(relation.role(), ignored -> new LinkedHashSet<>())
                        .add(new PersonPair(ownerId, relation.targetId()));

                    switch (relation.role()) {
                        case CHILD -> {
                            childrenByParent.computeIfAbsent(ownerId, ignored -> new LinkedHashSet<>()).add(relation.targetId());
                            parentsByChild.computeIfAbsent(relation.targetId(), ignored -> new LinkedHashSet<>()).add(ownerId);
                        }
                        case PARENT -> {
                            parentsByChild.computeIfAbsent(ownerId, ignored -> new LinkedHashSet<>()).add(relation.targetId());
                            childrenByParent.computeIfAbsent(relation.targetId(), ignored -> new LinkedHashSet<>()).add(ownerId);
                        }
                        case SPOUSE -> {
                            spousesByPerson.computeIfAbsent(ownerId, ignored -> new LinkedHashSet<>()).add(relation.targetId());
                            spousesByPerson.computeIfAbsent(relation.targetId(), ignored -> new LinkedHashSet<>()).add(ownerId);
                        }
                        case SIBLING -> {
                        }
                    }
                }
            }

            return new Graph(peopleById, parentsByChild, childrenByParent, pairsByRole, spousesByPerson);
        }

        private VPerson person(String personId) {
            return peopleById.get(personId);
        }

        private Set<PersonPair> pairs(RelationRole role) {
            return pairsByRole.getOrDefault(role, Set.of());
        }
    }
}

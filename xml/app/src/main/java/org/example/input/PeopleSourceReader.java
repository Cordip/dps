package org.example.input;

import org.example.model.CountFact;
import org.example.model.Gender;
import org.example.model.PersonId;
import org.example.model.PersonName;
import org.example.model.RelationMention;
import org.example.model.RelationRole;
import org.example.model.RelationSubtype;
import org.example.model.SourceRecord;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class PeopleSourceReader {
    private static final Set<String> FIRST_NAME_TAGS = Set.of("firstname", "first");
    private static final Set<String> LAST_NAME_TAGS = Set.of("surname", "family", "family-name", "lastname", "last");

    public List<SourceRecord> read(String xml) throws XMLStreamException {
        XMLStreamReader reader = inputFactory().createXMLStreamReader(new StringReader(xml));
        try {
            return read(reader);
        } finally {
            reader.close();
        }
    }

    private List<SourceRecord> read(XMLStreamReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT && tag(reader).equals("people")) {
                return readPeople(reader);
            }
        }
        return List.of();
    }

    private List<SourceRecord> readPeople(XMLStreamReader reader) throws XMLStreamException {
        List<SourceRecord> records = new ArrayList<>();
        int nestedDepth = 0;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (nestedDepth == 0 && tag(reader).equals("person")) {
                    records.add(readPerson(records.size() + 1, reader));
                } else {
                    nestedDepth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (nestedDepth == 0 && tag(reader).equals("people")) {
                    return List.copyOf(records);
                }
                if (nestedDepth > 0) {
                    nestedDepth--;
                }
            }
        }

        return List.copyOf(records);
    }

    private SourceRecord readPerson(int recordNumber, XMLStreamReader reader) throws XMLStreamException {
        LinkedHashSet<PersonId> ownerIds = new LinkedHashSet<>();
        List<String> invalidOwnerIdTokens = new ArrayList<>();
        List<RelationMention> relations = new ArrayList<>();
        List<CountFact> counts = new ArrayList<>();
        NameBuilder nameBuilder = new NameBuilder(attribute(reader, "name"));
        Gender gender = mergeGender(Gender.parse(attribute(reader, "gender")), Gender.parse(attribute(reader, "sex")));

        addOwnerIdTokens(attribute(reader, "id"), ownerIds, invalidOwnerIdTokens);

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                ElementData child = readElement(reader);
                if (child.name().equals("id")) {
                    addOwnerIdTokens(child.valueOrText(), ownerIds, invalidOwnerIdTokens);
                }
                nameBuilder.accept(child);
                gender = mergeGender(gender, genderFrom(child));
                parseCountOrWrapper(child, counts);
                parseRelationOrWrapper(child, relations);
            } else if (event == XMLStreamConstants.END_ELEMENT && tag(reader).equals("person")) {
                return new SourceRecord(
                    recordNumber,
                    List.copyOf(ownerIds),
                    List.copyOf(invalidOwnerIdTokens),
                    nameBuilder.name().orElse(null),
                    gender,
                    List.copyOf(relations),
                    List.copyOf(counts)
                );
            }
        }

        throw new XMLStreamException("Unclosed person element R" + recordNumber);
    }

    private ElementData readElement(XMLStreamReader reader) throws XMLStreamException {
        String elementName = tag(reader);
        Map<String, String> attributes = attributes(reader);
        StringBuilder text = new StringBuilder();
        List<ElementData> children = new ArrayList<>();

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                children.add(readElement(reader));
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                text.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT && tag(reader).equals(elementName)) {
                return new ElementData(elementName, attributes, text.toString(), children);
            }
        }

        throw new XMLStreamException("Unclosed element " + elementName);
    }

    private void parseCountOrWrapper(ElementData node, List<CountFact> counts) {
        if (node.name().equals("counts")) {
            for (ElementData child : node.children()) {
                parseDirectCount(child).ifPresent(counts::add);
            }
            return;
        }
        parseDirectCount(node).ifPresent(counts::add);
    }

    private Optional<CountFact> parseDirectCount(ElementData node) {
        Optional<RelationRole> role = countRoleFor(node.name());
        Optional<Integer> value = parseInteger(node.attribute("value")
            .or(() -> node.attribute("source"))
            .or(() -> node.attribute("val"))
            .or(() -> Optional.of(node.text())));
        if (role.isPresent() && value.isPresent()) {
            return Optional.of(new CountFact(role.get(), value.get()));
        }
        return Optional.empty();
    }

    private void parseRelationOrWrapper(ElementData node, List<RelationMention> relations) {
        Optional<TagRelation> relation = relationFor(node.name());
        if (relation.isEmpty()) {
            if (isRelationWrapper(node.name())) {
                for (ElementData child : node.children()) {
                    parseRelationOrWrapper(child, relations);
                }
            }
            return;
        }

        List<ElementData> relationChildren = node.children().stream()
            .filter(child -> relationFor(child.name()).isPresent())
            .toList();
        if (!relationChildren.isEmpty() && !hasOwnTarget(node)) {
            relationChildren.forEach(child -> parseRelationOrWrapper(child, relations));
            return;
        }

        relations.addAll(parseRelationTargets(node, relation.get()));
        relationChildren.forEach(child -> parseRelationOrWrapper(child, relations));
    }

    private List<RelationMention> parseRelationTargets(ElementData node, TagRelation relation) {
        TargetValues targetValues = targetValues(node);
        List<RelationMention> mentions = new ArrayList<>();
        for (PersonId targetId : targetValues.ids()) {
            mentions.add(RelationMention.byId(relation.role(), relation.subtype(), targetId));
        }
        targetValues.name().ifPresent(name ->
            mentions.add(RelationMention.byName(relation.role(), relation.subtype(), name)));
        return mentions;
    }

    private TargetValues targetValues(ElementData node) {
        LinkedHashSet<PersonId> ids = new LinkedHashSet<>();
        node.attribute("ref").ifPresent(value -> ids.addAll(parseRelationIds(value)));
        node.attribute("id").ifPresent(value -> ids.addAll(parseRelationIds(value)));
        if (!ids.isEmpty()) {
            return new TargetValues(List.copyOf(ids), Optional.empty());
        }

        List<String> nameSources = new ArrayList<>();
        node.attribute("value").ifPresent(nameSources::add);
        node.attribute("val").ifPresent(nameSources::add);
        node.attribute("name").ifPresent(nameSources::add);
        if (!node.text().isBlank()) {
            nameSources.add(node.text());
        }
        for (ElementData child : node.children()) {
            if (child.name().equals("id")) {
                nameSources.add(child.valueOrText());
            }
        }

        LinkedHashSet<String> realSources = new LinkedHashSet<>();
        for (String source : nameSources) {
            String trimmed = source.trim();
            if (!isPlaceholder(trimmed)) {
                realSources.add(trimmed);
            }
        }
        if (realSources.isEmpty()) {
            return new TargetValues(List.of(), Optional.empty());
        }

        String chosen = realSources.getFirst();
        if (allTokensAreIds(chosen)) {
            return new TargetValues(List.copyOf(parseRelationIds(chosen)), Optional.empty());
        }
        return new TargetValues(List.of(), Optional.of(PersonName.of(chosen)));
    }

    private Set<PersonId> parseRelationIds(String rawValue) {
        if (isPlaceholder(rawValue)) {
            return Set.of();
        }
        LinkedHashSet<PersonId> ids = new LinkedHashSet<>();
        for (String token : whitespaceTokens(rawValue)) {
            if (PersonId.isCanonical(token)) {
                ids.add(PersonId.parse(token));
            }
        }
        return ids;
    }

    private boolean allTokensAreIds(String rawValue) {
        if (isPlaceholder(rawValue)) {
            return false;
        }
        List<String> tokens = whitespaceTokens(rawValue);
        for (String token : tokens) {
            if (!PersonId.isCanonical(token)) {
                return false;
            }
        }
        return !tokens.isEmpty();
    }

    private void addOwnerIdTokens(
        String rawValue,
        LinkedHashSet<PersonId> ownerIds,
        List<String> invalidTokens
    ) {
        if (isPlaceholder(rawValue)) {
            return;
        }
        for (String token : whitespaceTokens(rawValue)) {
            if (PersonId.isCanonical(token)) {
                ownerIds.add(PersonId.parse(token));
            } else {
                invalidTokens.add(token);
            }
        }
    }

    private Optional<RelationRole> countRoleFor(String rawTag) {
        return switch (normalize(rawTag)) {
            case "parents-number", "parents-count" -> Optional.of(RelationRole.PARENT);
            case "spouse-number", "spouse-count", "spouses-number", "spouses-count" -> Optional.of(RelationRole.SPOUSE);
            case "siblings-number", "siblings-count" -> Optional.of(RelationRole.SIBLING);
            case "children-number", "children-count" -> Optional.of(RelationRole.CHILD);
            default -> Optional.empty();
        };
    }

    private Optional<TagRelation> relationFor(String rawTag) {
        return switch (normalize(rawTag)) {
            case "spouse", "spouce" -> Optional.of(new TagRelation(RelationRole.SPOUSE, RelationSubtype.GENERIC));
            case "wife" -> Optional.of(new TagRelation(RelationRole.SPOUSE, RelationSubtype.WIFE));
            case "husband" -> Optional.of(new TagRelation(RelationRole.SPOUSE, RelationSubtype.HUSBAND));
            case "father" -> Optional.of(new TagRelation(RelationRole.PARENT, RelationSubtype.FATHER));
            case "mother" -> Optional.of(new TagRelation(RelationRole.PARENT, RelationSubtype.MOTHER));
            case "parent" -> Optional.of(new TagRelation(RelationRole.PARENT, RelationSubtype.GENERIC));
            case "brother" -> Optional.of(new TagRelation(RelationRole.SIBLING, RelationSubtype.BROTHER));
            case "sister" -> Optional.of(new TagRelation(RelationRole.SIBLING, RelationSubtype.SISTER));
            case "sibling", "siblings" -> Optional.of(new TagRelation(RelationRole.SIBLING, RelationSubtype.GENERIC));
            case "son" -> Optional.of(new TagRelation(RelationRole.CHILD, RelationSubtype.SON));
            case "daughter" -> Optional.of(new TagRelation(RelationRole.CHILD, RelationSubtype.DAUGHTER));
            case "child", "children" -> Optional.of(new TagRelation(RelationRole.CHILD, RelationSubtype.GENERIC));
            default -> Optional.empty();
        };
    }

    private boolean isRelationWrapper(String rawTag) {
        return switch (normalize(rawTag)) {
            case "relations", "spouses", "parents", "siblings", "children" -> true;
            default -> false;
        };
    }

    private boolean hasOwnTarget(ElementData node) {
        return node.attribute("ref").isPresent()
            || node.attribute("id").isPresent()
            || node.attribute("value").isPresent()
            || node.attribute("val").isPresent()
            || node.attribute("name").isPresent()
            || !node.text().isBlank();
    }

    private Gender genderFrom(ElementData child) {
        if (!child.name().equals("gender") && !child.name().equals("sex")) {
            return Gender.UNKNOWN;
        }
        return Gender.parse(child.valueOrText());
    }

    private Gender mergeGender(Gender left, Gender right) {
        return left == Gender.UNKNOWN ? right : left;
    }

    private Optional<Integer> parseInteger(Optional<String> value) {
        if (value.isEmpty() || isPlaceholder(value.get())) {
            return Optional.empty();
        }
        String text = value.get().trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        int index = 0;
        boolean negative = false;
        int limit = -Integer.MAX_VALUE;
        char first = text.charAt(0);
        if (first == '-' || first == '+') {
            negative = first == '-';
            limit = negative ? Integer.MIN_VALUE : -Integer.MAX_VALUE;
            index = 1;
            if (index == text.length()) {
                return Optional.empty();
            }
        }

        int result = 0;
        int multmin = limit / 10;
        while (index < text.length()) {
            int digit = Character.digit(text.charAt(index++), 10);
            if (digit < 0 || result < multmin) {
                return Optional.empty();
            }
            result *= 10;
            if (result < limit + digit) {
                return Optional.empty();
            }
            result -= digit;
        }
        return Optional.of(negative ? result : -result);
    }

    private List<String> whitespaceTokens(String rawValue) {
        String value = rawValue.trim();
        if (value.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        int tokenStart = -1;
        for (int index = 0; index < value.length(); index++) {
            if (isLegacyRegexWhitespace(value.charAt(index))) {
                if (tokenStart >= 0) {
                    tokens.add(value.substring(tokenStart, index));
                    tokenStart = -1;
                }
            } else if (tokenStart < 0) {
                tokenStart = index;
            }
        }
        if (tokenStart >= 0) {
            tokens.add(value.substring(tokenStart));
        }
        return tokens;
    }

    private static boolean isLegacyRegexWhitespace(char character) {
        return switch (character) {
            case ' ', '\t', '\n', '\u000B', '\f', '\r' -> true;
            default -> false;
        };
    }

    private XMLInputFactory inputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setPropertyIfSupported(factory, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        setPropertyIfSupported(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        return factory;
    }

    private void setPropertyIfSupported(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // Some JDK XMLInputFactory implementations do not support every hardening flag.
        }
    }

    private Map<String, String> attributes(XMLStreamReader reader) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            result.put(normalize(reader.getAttributeLocalName(index)), reader.getAttributeValue(index));
        }
        return result;
    }

    private String attribute(XMLStreamReader reader, String name) {
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            if (reader.getAttributeLocalName(index).equalsIgnoreCase(name)) {
                return reader.getAttributeValue(index);
            }
        }
        return "";
    }

    private String tag(XMLStreamReader reader) {
        return normalize(reader.getLocalName());
    }

    private String normalize(String rawValue) {
        return rawValue.toLowerCase(Locale.ROOT);
    }

    private static boolean isPlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim();
        return normalized.equalsIgnoreCase("UNKNOWN") || normalized.equalsIgnoreCase("NONE");
    }

    private record ElementData(String name, Map<String, String> attributes, String text, List<ElementData> children) {
        ElementData {
            attributes = Map.copyOf(attributes);
            text = text.trim();
            children = List.copyOf(children);
        }

        Optional<String> attribute(String name) {
            return Optional.ofNullable(attributes.get(name));
        }

        String valueOrText() {
            return attribute("value")
                .or(() -> attribute("val"))
                .orElse(text)
                .trim();
        }

        Optional<PersonName> fullName() {
            String value = valueOrText();
            if (!isPlaceholder(value)) {
                return Optional.of(PersonName.of(value));
            }
            String first = "";
            String last = "";
            for (ElementData child : children) {
                if (FIRST_NAME_TAGS.contains(child.name()) && first.isBlank()) {
                    first = child.valueOrText();
                } else if (LAST_NAME_TAGS.contains(child.name()) && last.isBlank()) {
                    last = child.valueOrText();
                }
            }
            String combined = (first + " " + last).trim();
            return combined.isBlank() ? Optional.empty() : Optional.of(PersonName.ofParts(first, last));
        }
    }

    private final class NameBuilder {
        private Optional<PersonName> resolvedName;
        private String first = "";
        private String last = "";

        private NameBuilder(String attributeName) {
            resolvedName = isPlaceholder(attributeName)
                ? Optional.empty()
                : Optional.of(PersonName.of(attributeName));
        }

        private void accept(ElementData child) {
            if (resolvedName.isPresent()) {
                return;
            }
            if (FIRST_NAME_TAGS.contains(child.name()) && first.isBlank()) {
                first = child.valueOrText();
            } else if (LAST_NAME_TAGS.contains(child.name()) && last.isBlank()) {
                last = child.valueOrText();
            } else if (child.name().equals("fullname") || child.name().equals("name")) {
                resolvedName = child.fullName();
            }
        }

        private Optional<PersonName> name() {
            if (resolvedName.isPresent()) {
                return resolvedName;
            }
            String combined = (first + " " + last).trim();
            return combined.isBlank() ? Optional.empty() : Optional.of(PersonName.ofParts(first, last));
        }
    }

    private record TagRelation(RelationRole role, RelationSubtype subtype) {}

    private record TargetValues(List<PersonId> ids, Optional<PersonName> name) {
        TargetValues {
            ids = List.copyOf(ids);
            name = name == null ? Optional.empty() : name;
        }
    }
}

package org.example.output;

import org.example.structured.StructuredBrotherRef;
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
import org.example.structured.StructuredRelations;
import org.example.structured.StructuredSiblingRef;
import org.example.structured.StructuredSiblings;
import org.example.structured.StructuredSisterRef;
import org.example.structured.StructuredSonRef;
import org.example.structured.StructuredSpouseRef;
import org.example.structured.StructuredSpouses;
import org.example.structured.StructuredWifeRef;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.example.testxml.XmlSchemaAssertions.assertValidXml;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StructuredPeopleXmlWriterTest {
    @Test
    void writesSchemaValidJaxbXmlWithAllRelationTags() throws Exception {
        Path output = Files.createTempFile("people-structured", ".xml");
        StructuredPerson owner = person("P1", "Owner", "Person");
        List<StructuredPerson> targets = new ArrayList<>();
        for (int i = 2; i <= 13; i++) {
            targets.add(person("P" + i, "Target", Integer.toString(i)));
        }
        owner.relations().spouses().values().add(new StructuredWifeRef(targets.get(0), "source"));
        owner.relations().spouses().values().add(new StructuredHusbandRef(targets.get(1), "source"));
        owner.relations().spouses().values().add(new StructuredSpouseRef(targets.get(2), "derived-subtype"));
        owner.relations().parents().values().add(new StructuredFatherRef(targets.get(3), "source"));
        owner.relations().parents().values().add(new StructuredMotherRef(targets.get(4), "source"));
        owner.relations().parents().values().add(new StructuredParentRef(targets.get(5), "derived-subtype"));
        owner.relations().siblings().values().add(new StructuredBrotherRef(targets.get(6), "source"));
        owner.relations().siblings().values().add(new StructuredSisterRef(targets.get(7), "source"));
        owner.relations().siblings().values().add(new StructuredSiblingRef(targets.get(8), "derived-subtype"));
        owner.relations().children().values().add(new StructuredSonRef(targets.get(9), "source"));
        owner.relations().children().values().add(new StructuredDaughterRef(targets.get(10), "source"));
        owner.relations().children().values().add(new StructuredChildRef(targets.get(11), "derived-reciprocal"));
        owner = new StructuredPerson(
            owner.id(),
            owner.name(),
            owner.gender(),
            new StructuredCounts(
                new StructuredCount(3, 3, "match"),
                new StructuredCount(3, 3, "match"),
                new StructuredCount(3, 3, "match"),
                new StructuredCount(3, 3, "match")
            ),
            owner.relations()
        );

        List<StructuredPerson> people = new ArrayList<>();
        people.add(owner);
        people.addAll(targets);
        new StructuredPeopleXmlWriter().writeStructuredPeople(output, StructuredPeopleDocument.of(people));

        assertValidXml(output);
        Document document = parse(output);
        assertEquals("13", document.getDocumentElement().getAttribute("count"));
        for (String tag : List.of(
            "wife", "husband", "spouse",
            "father", "mother", "parent",
            "brother", "sister", "sibling",
            "son", "daughter", "child"
        )) {
            assertEquals(1, document.getElementsByTagName(tag).getLength(), tag);
        }
    }

    @Test
    void derivesDisplayNameFromTargetPerson() throws Exception {
        Path output = Files.createTempFile("people-structured-display", ".xml");
        StructuredPerson owner = person("P1", "Owner", "Person");
        StructuredPerson target = person("P2", "Correct", "Target");
        owner.relations().spouses().values().add(new StructuredSpouseRef(target, "source"));

        new StructuredPeopleXmlWriter().writeStructuredPeople(output, StructuredPeopleDocument.of(List.of(owner, target)));

        String xml = Files.readString(output);
        assertTrue(xml.contains("display-name=\"Correct Target\""));
        assertFalse(xml.contains("Wrong Person"));
    }

    @Test
    void rejectsInvalidDocumentBeforePublishingFinalOutput() throws Exception {
        Path output = Files.createTempFile("people-structured-stale", ".xml");
        Files.writeString(output, "stale");
        StructuredPerson owner = person("P1", "Owner", "Person");
        owner.relations().spouses().values().add(new StructuredSpouseRef(null, "source"));

        assertThrows(IOException.class, () ->
            new StructuredPeopleXmlWriter().writeStructuredPeople(output, StructuredPeopleDocument.of(List.of(owner))));
        assertEquals("stale", Files.readString(output));
    }

    private StructuredPerson person(String id, String first, String last) {
        return new StructuredPerson(
            id,
            new StructuredName(first, last),
            new StructuredGender("female"),
            StructuredCounts.empty(),
            new StructuredRelations(
                new StructuredSpouses(),
                new StructuredParents(),
                new StructuredSiblings(),
                new StructuredChildren()
            )
        );
    }

    private Document parse(Path xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(xml.toFile());
    }
}

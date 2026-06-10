package org.example.schema;

import org.junit.jupiter.api.Test;

import static org.example.testxml.XmlSchemaAssertions.assertInvalidXml;
import static org.example.testxml.XmlSchemaAssertions.assertValidXml;

final class PeopleStructuredSchemaTest {
    @Test
    void acceptsMinimalValidDocument() {
        assertValidXml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <people count="2">
              <person id="P1">
                <name><first>Alice</first><last>One</last></name>
                <gender value="female"/>
                <counts>
                  <spouses inferred="1" source="1" validation="match"/>
                  <parents inferred="0" validation="source-unknown"/>
                  <siblings inferred="0" validation="source-unknown"/>
                  <children inferred="0" validation="source-unknown"/>
                </counts>
                <relations>
                  <spouses><husband ref="P2" display-name="Bob Two" provenance="source"/></spouses>
                  <parents/>
                  <siblings/>
                  <children/>
                </relations>
              </person>
              <person id="P2">
                <name><first>Bob</first><last>Two</last></name>
                <counts>
                  <spouses inferred="1" source="1" validation="match"/>
                  <parents inferred="0" validation="source-unknown"/>
                  <siblings inferred="0" validation="source-unknown"/>
                  <children inferred="0" validation="source-unknown"/>
                </counts>
                <relations>
                  <spouses><wife ref="P1" display-name="Alice One" provenance="source"/></spouses>
                  <parents/>
                  <siblings/>
                  <children/>
                </relations>
              </person>
            </people>
            """);
    }

    @Test
    void rejectsDuplicatePersonId() {
        assertInvalidXml("""
            <people count="2">
              <person id="P1">%s</person>
              <person id="P1">%s</person>
            </people>
            """.formatted(emptyPersonBody(), emptyPersonBody()));
    }

    @Test
    void rejectsDanglingRelationReference() {
        assertInvalidXml("""
            <people count="1">
              <person id="P1">
                %s
                <relations>
                  <spouses><spouse ref="P2" display-name="" provenance="source"/></spouses>
                  <parents/>
                  <siblings/>
                  <children/>
                </relations>
              </person>
            </people>
            """.formatted(counts()));
    }

    @Test
    void rejectsP0PersonId() {
        assertInvalidXml("""
            <people count="1">
              <person id="P0">%s</person>
            </people>
            """.formatted(emptyPersonBody()));
    }

    @Test
    void rejectsP0RelationReference() {
        assertInvalidXml("""
            <people count="1">
              <person id="P1">
                %s
                <relations>
                  <spouses><spouse ref="P0" display-name="" provenance="source"/></spouses>
                  <parents/>
                  <siblings/>
                  <children/>
                </relations>
              </person>
            </people>
            """.formatted(counts()));
    }

    @Test
    void rejectsNegativeCounts() {
        assertInvalidXml("""
            <people count="1">
              <person id="P1">
                <counts>
                  <spouses inferred="-1" validation="source-unknown"/>
                  <parents inferred="0" validation="source-unknown"/>
                  <siblings inferred="0" validation="source-unknown"/>
                  <children inferred="0" validation="source-unknown"/>
                </counts>
                %s
              </person>
            </people>
            """.formatted(emptyRelations()));
        assertInvalidXml("""
            <people count="1">
              <person id="P1">
                <counts>
                  <spouses inferred="0" source="-1" validation="match"/>
                  <parents inferred="0" validation="source-unknown"/>
                  <siblings inferred="0" validation="source-unknown"/>
                  <children inferred="0" validation="source-unknown"/>
                </counts>
                %s
              </person>
            </people>
            """.formatted(emptyRelations()));
    }

    @Test
    void rejectsInvalidCountValidationValue() {
        assertInvalidXml("""
            <people count="1">
              <person id="P1">
                <counts>
                  <spouses inferred="0" validation="ambiguous-source"/>
                  <parents inferred="0" validation="source-unknown"/>
                  <siblings inferred="0" validation="source-unknown"/>
                  <children inferred="0" validation="source-unknown"/>
                </counts>
                %s
              </person>
            </people>
            """.formatted(emptyRelations()));
    }

    @Test
    void rejectsUnknownInvalidRelationTag() {
        assertInvalidXml("""
            <people count="2">
              <person id="P1">
                %s
                <relations>
                  <spouses><partner ref="P2" display-name="" provenance="source"/></spouses>
                  <parents/>
                  <siblings/>
                  <children/>
                </relations>
              </person>
              <person id="P2">%s</person>
            </people>
            """.formatted(counts(), emptyPersonBody()));
    }

    @Test
    void rejectsValidRelationTagInWrongGroup() {
        assertInvalidXml("""
            <people count="2">
              <person id="P1">
                %s
                <relations>
                  <spouses/>
                  <parents><son ref="P2" display-name="" provenance="source"/></parents>
                  <siblings/>
                  <children/>
                </relations>
              </person>
              <person id="P2">%s</person>
            </people>
            """.formatted(counts(), emptyPersonBody()));
    }

    @Test
    void rejectsEmptyNameParts() {
        assertInvalidXml("""
            <people count="1">
              <person id="P1">
                <name><first></first><last>One</last></name>
                %s
              </person>
            </people>
            """.formatted(emptyBodyAfterName()));
        assertInvalidXml("""
            <people count="1">
              <person id="P1">
                <name><first>Alice</first><last></last></name>
                %s
              </person>
            </people>
            """.formatted(emptyBodyAfterName()));
    }

    @Test
    void rejectsWhitespaceOnlyNameParts() {
        assertInvalidXml("""
            <people count="1">
              <person id="P1">
                <name><first>   </first><last>One</last></name>
                %s
              </person>
            </people>
            """.formatted(emptyBodyAfterName()));
        assertInvalidXml("""
            <people count="1">
              <person id="P1">
                <name><first>Alice</first><last>
                </last></name>
                %s
              </person>
            </people>
            """.formatted(emptyBodyAfterName()));
    }

    private String emptyPersonBody() {
        return counts() + emptyRelations();
    }

    private String emptyBodyAfterName() {
        return counts() + emptyRelations();
    }

    private String counts() {
        return """
            <counts>
              <spouses inferred="0" validation="source-unknown"/>
              <parents inferred="0" validation="source-unknown"/>
              <siblings inferred="0" validation="source-unknown"/>
              <children inferred="0" validation="source-unknown"/>
            </counts>
            """;
    }

    private String emptyRelations() {
        return """
            <relations>
              <spouses/>
              <parents/>
              <siblings/>
              <children/>
            </relations>
            """;
    }
}

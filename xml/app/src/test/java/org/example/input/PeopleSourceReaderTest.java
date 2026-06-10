package org.example.input;

import org.example.model.PersonId;
import org.example.model.Gender;
import org.example.model.RelationRole;
import org.example.model.RelationSubtype;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PeopleSourceReaderTest {
    private final PeopleSourceReader reader = new PeopleSourceReader();

    @Test
    void personAttributeIdIsOwnerPositionIdAndRecordIdsFollowSourceOrder() throws Exception {
        var records = reader.read("""
            <people>
              <person id="P7"/>
              <person id="P8"/>
            </people>
            """);

        assertEquals(List.of("R1", "R2"), records.stream()
            .map(record -> "R" + record.recordNumber())
            .toList());
        assertEquals(List.of(PersonId.parse("P7")), records.get(0).ownerIds());
        assertEquals(List.of(PersonId.parse("P8")), records.get(1).ownerIds());
    }

    @Test
    void directChildIdIsOwnerPositionId() throws Exception {
        var records = reader.read("""
            <people>
              <person><id>P42</id></person>
            </people>
            """);

        assertEquals(List.of(PersonId.parse("P42")), records.getFirst().ownerIds());
    }

    @Test
    void relationTargetIdIsNotOwnerPositionId() throws Exception {
        var records = reader.read("""
            <people>
              <person>
                <children>
                  <son id="P2"/>
                </children>
                <child><id>P3</id></child>
              </person>
            </people>
            """);

        assertTrue(records.getFirst().ownerIds().isEmpty());
    }

    @Test
    void invalidOwnerIdTokenStaysOnRecordForDecisionReporting() throws Exception {
        var records = reader.read("""
            <people>
              <person id="BAD"/>
            </people>
            """);

        assertTrue(records.getFirst().ownerIds().isEmpty());
        assertEquals(List.of("BAD"), records.getFirst().invalidOwnerIdTokens());
    }

    @Test
    void unicodeWhitespaceBetweenOwnerIdsKeepsOldRegexTokenSemantics() throws Exception {
        var records = reader.read("""
            <people>
              <person id="P2\u2003P3"/>
            </people>
            """);

        assertTrue(records.getFirst().ownerIds().isEmpty());
        assertEquals(List.of("P2\u2003P3"), records.getFirst().invalidOwnerIdTokens());
    }

    @Test
    void nameAttributeIsStoredOnSourceRecord() throws Exception {
        var records = reader.read("""
            <people>
              <person id="P1" name="Jane Doe"/>
            </people>
            """);

        assertEquals("Jane Doe", records.getFirst().name().display());
        assertEquals("Jane", records.getFirst().name().first());
        assertEquals("Doe", records.getFirst().name().last());
    }

    @Test
    void componentNameElementsPreserveFirstAndLastParts() throws Exception {
        var records = reader.read("""
            <people>
              <person id="P1">
                <first>Tessie</first>
                <last>Yin</last>
              </person>
            </people>
            """);

        assertEquals("Tessie Yin", records.getFirst().name().display());
        assertEquals("Tessie", records.getFirst().name().first());
        assertEquals("Yin", records.getFirst().name().last());
    }

    @Test
    void rawFactsNeededByNormalizerAreParsedFromSourceRecord() throws Exception {
        var records = reader.read("""
            <people>
              <person id="P1" name="Jane Doe" gender="female">
                <children-number value="2"/>
                <husband value="P2"/>
                <children>
                  <child>P99</child>
                  <daughter>Kid Name</daughter>
                </children>
              </person>
            </people>
            """);

        var record = records.getFirst();
        assertEquals(Gender.FEMALE, record.gender());
        assertEquals(1, record.counts().size());
        assertEquals(RelationRole.CHILD, record.counts().getFirst().role());
        assertEquals(2, record.counts().getFirst().value());
        assertEquals(List.of(
            "SPOUSE:HUSBAND:id=P2",
            "CHILD:GENERIC:id=P99",
            "CHILD:DAUGHTER:name=Kid Name"
        ), record.relations().stream()
            .map(relation -> relation.role()
                + ":" + relation.subtype()
                + (relation.targetId() == null ? "" : ":id=" + relation.targetId().value())
                + (relation.targetName() == null ? "" : ":name=" + relation.targetName().display()))
            .toList());
    }

    @Test
    void signedPositiveCountsKeepIntegerParseIntSemantics() throws Exception {
        var records = reader.read("""
            <people>
              <person id="P1">
                <children-number value="+2"/>
              </person>
            </people>
            """);

        assertEquals(2, records.getFirst().counts().getFirst().value());
    }

    @Test
    void negativeCountsStillFailAtCountFactBoundary() {
        assertThrows(IllegalArgumentException.class, () -> reader.read("""
            <people>
              <person id="P1">
                <children-number value="-1"/>
              </person>
            </people>
            """));
    }

    @Test
    void placeholderRelationTargetsAreIgnoredBeforeResolutionRules() throws Exception {
        var records = reader.read("""
            <people>
              <person name="Kaylene Startz">
                <parent value="UNKNOWN"/>
                <wife value="NONE"/>
                <spouce/>
              </person>
            </people>
            """);

        assertTrue(records.getFirst().relations().isEmpty());
    }
}

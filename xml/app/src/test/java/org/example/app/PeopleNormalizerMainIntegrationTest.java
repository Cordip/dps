package org.example.app;

import org.example.testxml.XmlSchemaAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PeopleNormalizerMainIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void writesEmptyStructuredPeopleXmlWhenInputExists() throws Exception {
        writeInput();

        ProcessResult result = runMainProcess();

        Path output = tempDir.resolve("app/output/people_structured.xml");
        assertEquals(0, result.exitCode(), result.combinedOutput());
        assertEmptyStructuredPeopleXml(output);
    }

    @Test
    void defaultRunWritesExplicitOwnerPeople() throws Exception {
        writeInput("""
            <?xml version="1.0" encoding="UTF-8"?>
            <people>
              <person id="P1" name="Jane Doe"/>
              <person><id>P2</id><children-number value="2"/><child>P99</child></person>
              <person name="Jane Doe"/>
              <person id="P4"><id>P5</id></person>
            </people>
            """);

        ProcessResult result = runMainProcess();

        assertEquals(0, result.exitCode(), result.combinedOutput());
        assertStructuredPeopleXmlContainsExplicitPeople(tempDir.resolve("app/output/people_structured.xml"));
    }

    @Test
    void validationFailureBlocksOutput() throws Exception {
        writeInput("""
            <?xml version="1.0" encoding="UTF-8"?>
            <people>
              <person id="P0" name="Generated Zero"/>
            </people>
            """);

        ProcessResult result = runMainProcess();

        assertEquals(1, result.exitCode());
        assertTrue(result.combinedOutput().contains("Validation failed"));
        assertFalse(Files.exists(tempDir.resolve("app/output/people_structured.xml")));
    }

    @Test
    void failsWithoutWritingOutputWhenInputIsMissing() throws Exception {
        Files.createDirectories(tempDir.resolve("app/output"));
        Files.writeString(tempDir.resolve("app/output/people_structured.xml"), "stale output");

        ProcessResult result = runMainProcess();

        assertEquals(1, result.exitCode());
        assertTrue(result.combinedOutput().contains("Missing input file: app/input/people.xml"));
        assertFalse(Files.exists(tempDir.resolve("app/output/people_structured.xml")));
    }

    @Test
    void ignoresArgumentsBecauseThereIsOnlyOneScenario() throws Exception {
        writeInput();

        ProcessResult result = runMainProcess("--input", "other.xml");

        assertEquals(0, result.exitCode(), result.combinedOutput());
        assertTrue(Files.exists(tempDir.resolve("app/output/people_structured.xml")));
    }

    @Test
    void uniqueFullNameRecordAttachesToSingleExistingOwner() throws Exception {
        writeInput("""
            <?xml version="1.0" encoding="UTF-8"?>
            <people>
              <person id="P1" name="Alice One"/>
              <person name="Alice One"><children-number value="2"/></person>
            </people>
            """);

        ProcessResult result = runMainProcess();

        Path outputPath = tempDir.resolve("app/output/people_structured.xml");
        String output = Files.readString(outputPath);
        assertEquals(0, result.exitCode(), result.combinedOutput());
        assertEquals(1, occurrences(output, "<person id=\""));
        assertTrue(output.contains("<person id=\"P1\">"));
        assertCount(outputPath, "children", "2", "0", "under-inferred");
    }

    @Test
    void directUniqueFullNameSelfReferenceDoesNotInvalidateWholeOutput() throws Exception {
        writeInput("""
            <?xml version="1.0" encoding="UTF-8"?>
            <people>
              <person id="P1" name="Alice One"/>
              <person name="Alice One"><spouse id="P1"/></person>
            </people>
            """);

        ProcessResult result = runMainProcess();

        String output = Files.readString(tempDir.resolve("app/output/people_structured.xml"));
        assertEquals(0, result.exitCode(), result.combinedOutput());
        assertEquals(1, occurrences(output, "<person id=\""));
        assertTrue(output.contains("<person id=\"P1\">"));
        assertFalse(output.contains("<spouse ref=\"P1\""));
    }

    @Test
    void restoredByNameRelationSubtypeCannotConflictWithOwnerGender() throws Exception {
        writeInput("""
            <?xml version="1.0" encoding="UTF-8"?>
            <people>
              <person id="P1" name="Alice One" gender="female">
                <spouse value="Beth Two"/>
              </person>
              <person id="P2" name="Beth Two" gender="female"/>
            </people>
            """);

        ProcessResult result = runMainProcess();

        String output = Files.readString(tempDir.resolve("app/output/people_structured.xml"));
        assertEquals(0, result.exitCode(), result.combinedOutput());
        assertEquals(2, occurrences(output, "<person id=\""));
        assertFalse(output.contains("ref=\"P2\""));
    }

    @Test
    void byNameSpouseWithMismatchedChildCountsIsPrunedBeforeValidation() throws Exception {
        writeInput("""
            <?xml version="1.0" encoding="UTF-8"?>
            <people>
              <person id="P1" name="Alice One">
                <children-number value="1"/>
                <spouse value="Bob Two"/>
              </person>
              <person id="P2" name="Bob Two">
                <children-number value="2"/>
              </person>
            </people>
            """);

        ProcessResult result = runMainProcess();

        String output = Files.readString(tempDir.resolve("app/output/people_structured.xml"));
        assertEquals(0, result.exitCode(), result.combinedOutput());
        assertEquals(2, occurrences(output, "<person id=\""));
        assertFalse(output.contains("ref=\"P2\""));
    }

    @Test
    void ambiguousNameOnlyRecordIsDiscardedAsUninformative() throws Exception {
        writeInput("""
            <?xml version="1.0" encoding="UTF-8"?>
            <people>
              <person id="P1" name="Alex Same"/>
              <person id="P2" name="Alex Same"/>
              <person name="Alex Same"/>
            </people>
            """);

        ProcessResult result = runMainProcess();

        String output = Files.readString(tempDir.resolve("app/output/people_structured.xml"));
        assertEquals(0, result.exitCode(), result.combinedOutput());
        assertEquals(2, occurrences(output, "<person id=\""));
        assertTrue(output.contains("<person id=\"P1\">"));
        assertTrue(output.contains("<person id=\"P2\">"));
    }

    @Test
    void duplicateFullNameCandidateRejectsProjectedByNameRelationConflict() throws Exception {
        writeInput("""
            <?xml version="1.0" encoding="UTF-8"?>
            <people>
              <person id="P1" name="Alex Same">
                <children-number value="1"/>
              </person>
              <person id="P2" name="Alex Same">
                <children-number value="2"/>
              </person>
              <person id="P3" name="Casey Spouse">
                <children-number value="2"/>
              </person>
              <person name="Alex Same">
                <children-number value="1"/>
                <spouse value="Casey Spouse"/>
              </person>
            </people>
            """);

        ProcessResult result = runMainProcess();

        String output = Files.readString(tempDir.resolve("app/output/people_structured.xml"));
        assertEquals(0, result.exitCode(), result.combinedOutput());
        assertEquals(3, occurrences(output, "<person id=\""));
        assertFalse(output.contains("<spouse ref=\"P3\""));
    }

    @Test
    void byNameRelationBatchPrunesParentOverflow() throws Exception {
        writeInput("""
            <?xml version="1.0" encoding="UTF-8"?>
            <people>
              <person id="P1" name="Alice One"/>
              <person id="P2" name="Bob Two"/>
              <person id="P3" name="Carol Three"/>
              <person id="P4" name="Kid Four">
                <parent value="Alice One"/>
                <parent value="Bob Two"/>
                <parent value="Carol Three"/>
              </person>
            </people>
            """);

        ProcessResult result = runMainProcess();

        Path outputPath = tempDir.resolve("app/output/people_structured.xml");
        String output = Files.readString(outputPath);
        assertEquals(0, result.exitCode(), result.combinedOutput());
        assertEquals(2, occurrences(output, "<parent ref=\""));
        assertRelation(outputPath, "parent", "P1", "Alice One", "source");
        assertRelation(outputPath, "parent", "P2", "Bob Two", "source");
        assertFalse(output.contains("<parent ref=\"P3\""));
    }

    @Test
    void fullDatasetOutputMatchesAcceptedShape() throws Exception {
        Path source = projectInputFile();
        Files.createDirectories(tempDir.resolve("app/input"));
        Files.copy(source, tempDir.resolve("app/input/people.xml"));

        ProcessResult result = runMainProcess();

        Path output = tempDir.resolve("app/output/people_structured.xml");
        assertEquals(0, result.exitCode(), result.combinedOutput());
        XmlSchemaAssertions.assertValidXml(output);
        assertRootCountMatchesPersonElements(output);
        assertNoGeneratedIds(output);
        assertFullDatasetShape(output);
    }

    private void writeInput() throws IOException {
        writeInput("""
            <?xml version="1.0" encoding="UTF-8"?>
            <people count="0"/>
            """);
    }

    private void writeInput(String xml) throws IOException {
        Files.createDirectories(tempDir.resolve("app/input"));
        Files.writeString(tempDir.resolve("app/input/people.xml"), xml);
    }

    private void assertEmptyStructuredPeopleXml(Path output) throws Exception {
        assertTrue(Files.exists(output));
        var document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(output.toFile());
        var root = document.getDocumentElement();
        assertEquals("people", root.getTagName());
        assertEquals("0", root.getAttribute("count"));
        assertEquals(0, root.getElementsByTagName("person").getLength());
    }

    private void assertStructuredPeopleXmlContainsExplicitPeople(Path output) throws Exception {
        assertTrue(Files.exists(output));
        var document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(output.toFile());
        var root = document.getDocumentElement();
        assertEquals("people", root.getTagName());
        assertEquals("2", root.getAttribute("count"));
        var people = root.getElementsByTagName("person");
        assertEquals(2, people.getLength());
        org.w3c.dom.Element first = (org.w3c.dom.Element) people.item(0);
        assertEquals("P1", first.getAttribute("id"));
        assertFalse(first.hasAttribute("name"));
        assertFalse(first.hasAttribute("gender"));
        assertEquals(1, first.getElementsByTagName("name").getLength());
        assertEquals(1, first.getElementsByTagName("counts").getLength());
        assertEquals(1, first.getElementsByTagName("relations").getLength());
        org.w3c.dom.Element second = (org.w3c.dom.Element) people.item(1);
        assertEquals("P2", second.getAttribute("id"));
        assertEquals(1, second.getElementsByTagName("counts").getLength());
        assertEquals(1, second.getElementsByTagName("relations").getLength());
    }

    private ProcessResult runMainProcess(String... args) throws IOException, InterruptedException {
        return runProcess("org.example.app.PeopleNormalizerMain", args);
    }

    private Path projectInputFile() {
        List<Path> candidates = List.of(
            Path.of("app/input/people.xml"),
            Path.of("input/people.xml")
        );
        return candidates.stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Cannot locate project people.xml"));
    }

    private void assertRootCountMatchesPersonElements(Path output) throws Exception {
        var document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(output.toFile());
        var root = document.getDocumentElement();
        assertEquals(
            root.getElementsByTagName("person").getLength(),
            Integer.parseInt(root.getAttribute("count"))
        );
    }

    private void assertNoGeneratedIds(Path output) throws Exception {
        var document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(output.toFile());
        var people = document.getDocumentElement().getElementsByTagName("person");
        for (int i = 0; i < people.getLength(); i++) {
            org.w3c.dom.Element person = (org.w3c.dom.Element) people.item(i);
            assertFalse(person.getAttribute("id").startsWith("GEN_"));
        }
    }

    private void assertFullDatasetShape(Path output) throws Exception {
        var document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(output.toFile());
        assertEquals(32742, document.getElementsByTagName("person").getLength());
        assertEquals(32742, document.getElementsByTagName("name").getLength());
        assertEquals(32742, document.getElementsByTagName("gender").getLength());
        assertEquals(145922, relationCount(document));
    }

    private int relationCount(org.w3c.dom.Document document) {
        int total = 0;
        for (String tag : List.of(
            "wife", "husband", "spouse",
            "father", "mother", "parent",
            "brother", "sister", "sibling",
            "son", "daughter", "child"
        )) {
            total += document.getElementsByTagName(tag).getLength();
        }
        return total;
    }

    private void assertCount(Path output, String tag, String source, String inferred, String validation)
        throws Exception {
        var document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(output.toFile());
        org.w3c.dom.Element person = (org.w3c.dom.Element) document.getDocumentElement()
            .getElementsByTagName("person")
            .item(0);
        org.w3c.dom.Element counts = directChild(person, "counts");
        org.w3c.dom.Element count = directChild(counts, tag);
        assertEquals(source, count.getAttribute("source"));
        assertEquals(inferred, count.getAttribute("inferred"));
        assertEquals(validation, count.getAttribute("validation"));
    }

    private void assertRelation(Path output, String tag, String ref, String displayName, String provenance)
        throws Exception {
        var document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(output.toFile());
        var relations = document.getElementsByTagName(tag);
        for (int i = 0; i < relations.getLength(); i++) {
            org.w3c.dom.Element relation = (org.w3c.dom.Element) relations.item(i);
            if (ref.equals(relation.getAttribute("ref"))) {
                assertEquals(displayName, relation.getAttribute("display-name"));
                assertEquals(provenance, relation.getAttribute("provenance"));
                return;
            }
        }
        throw new AssertionError("Missing <" + tag + " ref=\"" + ref + "\">");
    }

    private org.w3c.dom.Element directChild(org.w3c.dom.Element parent, String tag) {
        var nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof org.w3c.dom.Element element && tag.equals(element.getTagName())) {
                return element;
            }
        }
        throw new AssertionError("Missing child <" + tag + "> under <" + parent.getTagName() + ">");
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private ProcessResult runProcess(String mainClass, String... args) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        List<String> command = new ArrayList<>(List.of(
            java.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            mainClass
        ));
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command)
            .directory(tempDir.toFile())
            .redirectErrorStream(true)
            .start();

        String output = new String(process.getInputStream().readAllBytes());
        return new ProcessResult(process.waitFor(), output);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private record ProcessResult(int exitCode, String combinedOutput) {
    }
}

package org.example.app;

import org.example.facts.FactIndex;
import org.example.input.PeopleSourceReader;
import org.example.model.SourceRecord;
import org.example.output.StructuredPeopleXmlWriter;
import org.example.owner.OwnerResolver;
import org.example.relation.RelationResolver;
import org.example.relation.ResolvedRelation;
import org.example.structured.StructuredPeopleAssembler;
import org.example.structured.StructuredPeopleDocument;
import org.example.validation.OutputHardRuleValidator;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PeopleNormalizerMain {
    private static final Path INPUT_PATH = Path.of("app/input/people.xml");
    private static final Path OUTPUT_PATH = Path.of("app/output/people_structured.xml");

    private PeopleNormalizerMain() {
    }

    public static void main(String[] args) {
        System.exit(processPeople());
    }

    private static int processPeople() {
        if (!Files.isRegularFile(INPUT_PATH)) {
            return failWithoutOutput("Missing input file: " + INPUT_PATH);
        }

        try {
            List<SourceRecord> records = new PeopleSourceReader().read(Files.readString(INPUT_PATH));
            RelationResolver relationResolver = new RelationResolver();
            FactIndex facts = new OwnerResolver(relationResolver).resolve(records);
            List<ResolvedRelation> relations = relationResolver.resolve(facts);
            StructuredPeopleDocument document = new StructuredPeopleAssembler().assemble(facts, relations);
            if (!new OutputHardRuleValidator().isValid(document)) {
                return failWithoutOutput("Validation failed");
            }
            new StructuredPeopleXmlWriter().writeStructuredPeople(OUTPUT_PATH, document);
            return 0;
        } catch (IOException | XMLStreamException exception) {
            return failWithoutOutput("Failed to normalize people.xml: " + exception.getMessage());
        }
    }

    private static int failWithoutOutput(String message) {
        try {
            Files.deleteIfExists(OUTPUT_PATH);
        } catch (IOException exception) {
            System.err.println(message);
            System.err.println("Failed to remove stale output: " + exception.getMessage());
            return 1;
        }
        System.err.println(message);
        return 1;
    }
}

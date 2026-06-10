package org.example.output;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import org.example.structured.StructuredPeopleDocument;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class StructuredPeopleXmlWriter {
    public void writeStructuredPeople(Path outputPath, StructuredPeopleDocument document) throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempFile = parent == null
            ? Files.createTempFile("people-structured", ".xml.tmp")
            : Files.createTempFile(parent, "people-structured", ".xml.tmp");
        try {
            document.syncCount();
            Marshaller marshaller = JAXBContext.newInstance(StructuredPeopleDocument.class).createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            marshaller.setSchema(schema());
            try (OutputStream output = Files.newOutputStream(tempFile)) {
                marshaller.marshal(document, output);
            }
            Files.move(tempFile, outputPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (JAXBException | SAXException exception) {
            throw new IOException("Failed to write schema-valid structured people XML", exception);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private Schema schema() throws SAXException, IOException {
        URL schemaUrl = StructuredPeopleXmlWriter.class.getResource("/people-structured.xsd");
        if (schemaUrl == null) {
            throw new IOException("Missing people-structured.xsd");
        }
        return SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(schemaUrl);
    }
}

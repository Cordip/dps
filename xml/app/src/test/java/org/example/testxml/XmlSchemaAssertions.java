package org.example.testxml;

import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class XmlSchemaAssertions {
    private XmlSchemaAssertions() {
    }

    public static void assertValidXml(String xml) {
        assertDoesNotThrow(() -> schema().newValidator().validate(new StreamSource(new StringReader(xml))));
    }

    public static void assertInvalidXml(String xml) {
        assertThrows(SAXException.class, () -> schema().newValidator().validate(new StreamSource(new StringReader(xml))));
    }

    public static void assertValidXml(Path xmlPath) {
        assertDoesNotThrow(() -> schema().newValidator().validate(new StreamSource(xmlPath.toFile())));
    }

    private static Schema schema() throws SAXException, IOException {
        URL resource = XmlSchemaAssertions.class.getResource("/people-structured.xsd");
        if (resource == null) {
            throw new IOException("Missing people-structured.xsd");
        }
        return SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(resource);
    }
}

package org.example.structured;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;

@XmlAccessorType(XmlAccessType.FIELD)
public final class StructuredGender {
    @XmlAttribute(name = "value", required = true)
    private String value;

    public StructuredGender() {
    }

    public StructuredGender(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

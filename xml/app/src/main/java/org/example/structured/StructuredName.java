package org.example.structured;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

@XmlAccessorType(XmlAccessType.FIELD)
public final class StructuredName {
    @XmlElement(name = "first", required = true)
    private String first;

    @XmlElement(name = "last", required = true)
    private String last;

    public StructuredName() {
    }

    public StructuredName(String first, String last) {
        this.first = first;
        this.last = last;
    }

    public String first() {
        return first;
    }

    public String last() {
        return last;
    }

    public String display() {
        return first + " " + last;
    }
}

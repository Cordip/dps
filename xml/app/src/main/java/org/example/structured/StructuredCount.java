package org.example.structured;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;

@XmlAccessorType(XmlAccessType.FIELD)
public final class StructuredCount {
    @XmlAttribute(name = "source")
    private Integer source;

    @XmlAttribute(name = "inferred", required = true)
    private int inferred;

    @XmlAttribute(name = "validation", required = true)
    private String validation;

    public StructuredCount() {
    }

    public StructuredCount(Integer source, int inferred, String validation) {
        this.source = source;
        this.inferred = inferred;
        this.validation = validation;
    }

    public static StructuredCount unknown(int inferred) {
        return new StructuredCount(null, inferred, "source-unknown");
    }

    public Integer source() {
        return source;
    }

    public int inferred() {
        return inferred;
    }

    public String validation() {
        return validation;
    }
}

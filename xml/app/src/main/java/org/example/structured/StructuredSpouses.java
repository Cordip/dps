package org.example.structured;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElements;

import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public final class StructuredSpouses {
    @XmlElements({
        @XmlElement(name = "wife", type = StructuredWifeRef.class),
        @XmlElement(name = "husband", type = StructuredHusbandRef.class),
        @XmlElement(name = "spouse", type = StructuredSpouseRef.class)
    })
    private List<StructuredRelationRef> values = new ArrayList<>();

    public StructuredSpouses() {
    }

    public StructuredSpouses(List<StructuredRelationRef> values) {
        this.values = new ArrayList<>(values);
    }

    public List<StructuredRelationRef> values() {
        return values;
    }
}

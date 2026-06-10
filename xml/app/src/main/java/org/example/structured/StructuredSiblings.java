package org.example.structured;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElements;

import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public final class StructuredSiblings {
    @XmlElements({
        @XmlElement(name = "brother", type = StructuredBrotherRef.class),
        @XmlElement(name = "sister", type = StructuredSisterRef.class),
        @XmlElement(name = "sibling", type = StructuredSiblingRef.class)
    })
    private List<StructuredRelationRef> values = new ArrayList<>();

    public StructuredSiblings() {
    }

    public StructuredSiblings(List<StructuredRelationRef> values) {
        this.values = new ArrayList<>(values);
    }

    public List<StructuredRelationRef> values() {
        return values;
    }
}

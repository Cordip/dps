package org.example.structured;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElements;

import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public final class StructuredParents {
    @XmlElements({
        @XmlElement(name = "father", type = StructuredFatherRef.class),
        @XmlElement(name = "mother", type = StructuredMotherRef.class),
        @XmlElement(name = "parent", type = StructuredParentRef.class)
    })
    private List<StructuredRelationRef> values = new ArrayList<>();

    public StructuredParents() {
    }

    public StructuredParents(List<StructuredRelationRef> values) {
        this.values = new ArrayList<>(values);
    }

    public List<StructuredRelationRef> values() {
        return values;
    }
}

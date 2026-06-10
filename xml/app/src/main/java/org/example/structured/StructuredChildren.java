package org.example.structured;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElements;

import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public final class StructuredChildren {
    @XmlElements({
        @XmlElement(name = "son", type = StructuredSonRef.class),
        @XmlElement(name = "daughter", type = StructuredDaughterRef.class),
        @XmlElement(name = "child", type = StructuredChildRef.class)
    })
    private List<StructuredRelationRef> values = new ArrayList<>();

    public StructuredChildren() {
    }

    public StructuredChildren(List<StructuredRelationRef> values) {
        this.values = new ArrayList<>(values);
    }

    public List<StructuredRelationRef> values() {
        return values;
    }
}

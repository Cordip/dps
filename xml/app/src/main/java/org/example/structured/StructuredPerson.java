package org.example.structured;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlID;

@XmlAccessorType(XmlAccessType.FIELD)
public final class StructuredPerson {
    @XmlAttribute(name = "id", required = true)
    @XmlID
    private String id;

    @XmlElement(name = "name")
    private StructuredName name;

    @XmlElement(name = "gender")
    private StructuredGender gender;

    @XmlElement(name = "counts", required = true)
    private StructuredCounts counts = StructuredCounts.empty();

    @XmlElement(name = "relations", required = true)
    private StructuredRelations relations = new StructuredRelations();

    public StructuredPerson() {
    }

    public StructuredPerson(
        String id,
        StructuredName name,
        StructuredGender gender,
        StructuredCounts counts,
        StructuredRelations relations
    ) {
        this.id = id;
        this.name = name;
        this.gender = gender;
        this.counts = counts;
        this.relations = relations;
    }

    public static StructuredPerson basic(String id, StructuredName name) {
        return new StructuredPerson(id, name, null, StructuredCounts.empty(), new StructuredRelations());
    }

    public String id() {
        return id;
    }

    public StructuredName name() {
        return name;
    }

    public StructuredGender gender() {
        return gender;
    }

    public StructuredCounts counts() {
        return counts;
    }

    public StructuredRelations relations() {
        return relations;
    }
}

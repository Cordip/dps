package org.example.structured;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlIDREF;
import jakarta.xml.bind.annotation.XmlTransient;
import org.example.model.RelationRole;
import org.example.model.RelationSubtype;

@XmlTransient
@XmlAccessorType(XmlAccessType.FIELD)
public abstract class StructuredRelationRef {
    @XmlAttribute(name = "ref", required = true)
    @XmlIDREF
    private StructuredPerson target;

    @XmlAttribute(name = "provenance", required = true)
    private String provenance;

    protected StructuredRelationRef() {
    }

    protected StructuredRelationRef(StructuredPerson target, String provenance) {
        this.target = target;
        this.provenance = provenance;
    }

    public StructuredPerson target() {
        return target;
    }

    public String targetId() {
        return target == null ? "" : target.id();
    }

    public String provenance() {
        return provenance;
    }

    @XmlAttribute(name = "display-name", required = true)
    public String getDisplayName() {
        return target == null || target.name() == null ? "" : target.name().display();
    }

    public abstract RelationRole role();

    public abstract RelationSubtype subtype();
}

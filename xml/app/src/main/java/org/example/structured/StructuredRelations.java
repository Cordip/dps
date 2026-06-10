package org.example.structured;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public final class StructuredRelations {
    @XmlElement(name = "spouses", required = true)
    private StructuredSpouses spouses = new StructuredSpouses();

    @XmlElement(name = "parents", required = true)
    private StructuredParents parents = new StructuredParents();

    @XmlElement(name = "siblings", required = true)
    private StructuredSiblings siblings = new StructuredSiblings();

    @XmlElement(name = "children", required = true)
    private StructuredChildren children = new StructuredChildren();

    public StructuredRelations() {
    }

    public StructuredRelations(
        StructuredSpouses spouses,
        StructuredParents parents,
        StructuredSiblings siblings,
        StructuredChildren children
    ) {
        this.spouses = spouses;
        this.parents = parents;
        this.siblings = siblings;
        this.children = children;
    }

    public StructuredSpouses spouses() {
        return spouses;
    }

    public StructuredParents parents() {
        return parents;
    }

    public StructuredSiblings siblings() {
        return siblings;
    }

    public StructuredChildren children() {
        return children;
    }

    public List<StructuredRelationRef> all() {
        List<StructuredRelationRef> result = new ArrayList<>();
        result.addAll(spouses.values());
        result.addAll(parents.values());
        result.addAll(siblings.values());
        result.addAll(children.values());
        return result;
    }
}

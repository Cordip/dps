package org.example.structured;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

@XmlAccessorType(XmlAccessType.FIELD)
public final class StructuredCounts {
    @XmlElement(name = "spouses", required = true)
    private StructuredCount spouses = StructuredCount.unknown(0);

    @XmlElement(name = "parents", required = true)
    private StructuredCount parents = StructuredCount.unknown(0);

    @XmlElement(name = "siblings", required = true)
    private StructuredCount siblings = StructuredCount.unknown(0);

    @XmlElement(name = "children", required = true)
    private StructuredCount children = StructuredCount.unknown(0);

    public StructuredCounts() {
    }

    public StructuredCounts(
        StructuredCount spouses,
        StructuredCount parents,
        StructuredCount siblings,
        StructuredCount children
    ) {
        this.spouses = spouses;
        this.parents = parents;
        this.siblings = siblings;
        this.children = children;
    }

    public static StructuredCounts empty() {
        return new StructuredCounts(
            StructuredCount.unknown(0),
            StructuredCount.unknown(0),
            StructuredCount.unknown(0),
            StructuredCount.unknown(0)
        );
    }

    public StructuredCount spouses() {
        return spouses;
    }

    public StructuredCount parents() {
        return parents;
    }

    public StructuredCount siblings() {
        return siblings;
    }

    public StructuredCount children() {
        return children;
    }
}

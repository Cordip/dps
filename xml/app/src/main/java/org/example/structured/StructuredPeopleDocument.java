package org.example.structured;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;

@XmlRootElement(name = "people")
@XmlAccessorType(XmlAccessType.FIELD)
public final class StructuredPeopleDocument {
    @XmlAttribute(name = "count", required = true)
    private int count;

    @XmlElement(name = "person")
    private List<StructuredPerson> people = new ArrayList<>();

    public StructuredPeopleDocument() {
    }

    public StructuredPeopleDocument(List<StructuredPerson> people) {
        this.people = new ArrayList<>(people);
        syncCount();
    }

    public static StructuredPeopleDocument of(List<StructuredPerson> people) {
        return new StructuredPeopleDocument(people);
    }

    public int count() {
        return count;
    }

    public List<StructuredPerson> people() {
        return people;
    }

    public void syncCount() {
        count = people == null ? 0 : people.size();
    }
}

package org.example.relation;

import org.example.model.PersonId;

public record TargetResolution(PersonId selectedTargetId, boolean hasPositiveSupport, boolean rejectedConflict) {}

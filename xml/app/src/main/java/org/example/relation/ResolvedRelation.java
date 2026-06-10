package org.example.relation;

import org.example.model.PersonId;
import org.example.model.RelationMention;

public record ResolvedRelation(PersonId ownerId, RelationMention relation, PersonId targetId) {}

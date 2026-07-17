package com.example.sso.session.policy;

import com.example.sso.metadata.AttributePredicateGroup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Targets a session policy at the users matched by an AND of metadata conditions ("dept = eng AND level =
 * senior AND clearance EXISTS"). At least one condition; each is a bounded {@code key <operator> value}. Maps to
 * the {@link AttributePredicateGroup} the resolver evaluates. The session twin of {@code authpolicy}'s request.
 */
public record AttributeTargetRequest(@NotEmpty @Valid List<AttributeConditionRequest> conditions) {

    public AttributePredicateGroup toGroup() {
        return new AttributePredicateGroup(conditions.stream().map(AttributeConditionRequest::toPredicate).toList());
    }
}

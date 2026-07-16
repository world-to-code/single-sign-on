package com.example.sso.mapping;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
import java.util.List;

/**
 * One condition of a mapping rule: {@code attrKey <attrOp> attrValue/attrValues}. A rule's conditions are
 * AND-combined. Positive operators only — EQUALS/CONTAINS (a scalar value), EXISTS (value-less), or IN (a
 * non-empty value list) — so each condition's cohort is index-able and the rule's cohort is their intersection.
 */
public record MappingCondition(String attrKey, AttributeOperator attrOp, String attrValue, List<String> attrValues) {

    public MappingCondition {
        attrValues = attrValues == null ? List.of() : List.copyOf(attrValues);
        boolean hasValue = attrValue != null;
        boolean hasValues = !attrValues.isEmpty();
        if (attrOp.requiresValue() != hasValue) {
            throw new IllegalArgumentException("EQUALS/CONTAINS require a value; EXISTS/IN must not carry one");
        }
        if (attrOp.requiresValueList() != hasValues) {
            throw new IllegalArgumentException("IN needs a non-empty value list; other operators carry none");
        }
    }

    /** A scalar or value-less condition (EQUALS/CONTAINS/EXISTS) — no value list. */
    public MappingCondition(String attrKey, AttributeOperator attrOp, String attrValue) {
        this(attrKey, attrOp, attrValue, List.of());
    }

    /** This condition as an {@link AttributePredicate} for in-memory matching. */
    public AttributePredicate toPredicate() {
        return attrOp == AttributeOperator.IN
                ? AttributePredicate.in(attrKey, attrValues)
                : new AttributePredicate(attrKey, attrOp, attrValue);
    }
}

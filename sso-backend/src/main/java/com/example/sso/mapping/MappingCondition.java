package com.example.sso.mapping;

import com.example.sso.metadata.AttributeOperator;

/**
 * One condition of a mapping rule: {@code attrKey <attrOp> attrValue}. A rule's conditions are AND-combined —
 * a user is assigned only when they satisfy every one. Positive operators only (EQUALS with a value, EXISTS
 * value-less), so each condition's cohort is index-able and the rule's cohort is their intersection.
 */
public record MappingCondition(String attrKey, AttributeOperator attrOp, String attrValue) {
}

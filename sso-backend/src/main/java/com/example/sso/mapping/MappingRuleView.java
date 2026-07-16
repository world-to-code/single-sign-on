package com.example.sso.mapping;

import com.example.sso.metadata.AttributeOperator;

/**
 * Admin view of a mapping rule: its predicate (key + operator + value, value null for EXISTS), its target
 * (kind + id + resolved name), and how many users it currently has assigned (its provenance count).
 */
public record MappingRuleView(String id, String attrKey, AttributeOperator attrOp, String attrValue,
                              MappingTargetKind thenKind, String targetId, String targetName, int assignedCount) {
}

package com.example.sso.mapping;

/**
 * Admin view of a mapping rule: its predicate, its target (kind + id + resolved name), and how many users it
 * currently has assigned (its provenance count).
 */
public record MappingRuleView(String id, String attrKey, String attrValue, MappingTargetKind thenKind,
                              String targetId, String targetName, int assignedCount) {
}

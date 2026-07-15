package com.example.sso.mapping;

/**
 * Admin view of a mapping rule: its predicate, its target group (id + resolved name), and how many users it
 * currently has assigned (its provenance count).
 */
public record MappingRuleView(String id, String attrKey, String attrValue, MappingTargetKind thenKind,
                              String groupId, String groupName, int assignedCount) {
}

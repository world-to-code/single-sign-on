package com.example.sso.mapping;

import java.util.List;

/**
 * Admin view of a mapping rule: its conditions (AND-combined), its target (kind + id + resolved name), and how
 * many users it currently has assigned (its provenance count).
 */
public record MappingRuleView(String id, List<MappingCondition> conditions, MappingTargetKind thenKind,
                              String targetId, String targetName, int assignedCount) {
}

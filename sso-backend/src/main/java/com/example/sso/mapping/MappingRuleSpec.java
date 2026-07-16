package com.example.sso.mapping;

import com.example.sso.metadata.AttributeOperator;
import java.util.List;
import java.util.UUID;

/**
 * Create/update command for a mapping rule: assign the users satisfying ALL {@code conditions} (AND-combined) to
 * {@code targetId} — a group or a role, per {@code thenKind}. Each condition uses a positive operator (EQUALS
 * with a value, EXISTS value-less). Org scope is taken from the acting tier. At least one condition is required.
 */
public record MappingRuleSpec(List<MappingCondition> conditions, MappingTargetKind thenKind, UUID targetId) {

    /** A single-condition rule — the common case and the shape every rule had before AND was introduced. */
    public static MappingRuleSpec single(String attrKey, AttributeOperator attrOp, String attrValue,
            MappingTargetKind thenKind, UUID targetId) {
        return new MappingRuleSpec(List.of(new MappingCondition(attrKey, attrOp, attrValue)), thenKind, targetId);
    }
}

package com.example.sso.mapping;

import com.example.sso.metadata.AttributeOperator;
import java.util.UUID;

/**
 * Create/update command for a mapping rule: assign the users a predicate ({@code attrKey <attrOp> attrValue})
 * matches to {@code targetId} — a group or a role, per {@code thenKind}. A mapping rule allows only the positive
 * operators EQUALS and EXISTS (EXISTS carries a null value). Org scope is taken from the acting tier.
 */
public record MappingRuleSpec(String attrKey, AttributeOperator attrOp, String attrValue, MappingTargetKind thenKind,
                              UUID targetId) {
}

package com.example.sso.mapping;

import java.util.UUID;

/**
 * Create/update command for a mapping rule: assign the users carrying {@code attrKey = attrValue} to
 * {@code targetId} — a group or a role, per {@code thenKind}. Org scope is taken from the acting tier.
 */
public record MappingRuleSpec(String attrKey, String attrValue, MappingTargetKind thenKind, UUID targetId) {
}

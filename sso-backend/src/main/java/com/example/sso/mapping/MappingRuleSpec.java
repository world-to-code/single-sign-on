package com.example.sso.mapping;

import java.util.UUID;

/**
 * Create/update command for a mapping rule: assign the users carrying {@code attrKey = attrValue} to
 * {@code groupId}. The only target kind today is a group, so it is implicit; org scope is taken from the acting
 * tier, not the command.
 */
public record MappingRuleSpec(String attrKey, String attrValue, UUID groupId) {
}

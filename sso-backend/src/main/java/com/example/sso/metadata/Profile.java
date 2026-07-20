package com.example.sso.metadata;

import java.util.UUID;

/**
 * One named profile: the unit that groups attribute definitions and that mappings carry values between.
 *
 * @param connectorId       the identity source this profile describes, or null for the tenant's own
 * @param system            the tenant's own profile — always present, never deletable
 * @param defaultForCreation what a manually created user is given; at most one per organization
 */
public record Profile(UUID id, String name, ProfileKind kind, UUID connectorId, boolean system,
                      boolean defaultForCreation) {
}

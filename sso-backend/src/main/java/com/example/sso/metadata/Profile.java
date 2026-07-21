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

    /**
     * Whether users may live on this profile — created on it, moved to it, or given values mapped into it.
     *
     * <p>Only the tenant's own. A source profile describes what SCIM or a directory SENDS us, not what a person
     * is, and it dies with its connector: {@code profile.connector_id} cascades while {@code app_user.profile_id}
     * is ON DELETE SET NULL, so deleting a connector would silently reset every bound user's schema with nothing
     * recorded. The rule lives on the profile because the three places that need it want three different things
     * to happen when it fails, and only the question is shared.
     */
    public boolean governsUsers() {
        return kind == ProfileKind.TENANT;
    }
}

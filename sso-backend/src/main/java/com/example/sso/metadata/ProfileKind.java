package com.example.sso.metadata;

/**
 * What a profile describes: the tenant's own people, or the shape of one identity source it reads from.
 *
 * <p>Every value except {@link #TENANT} belongs to a connector and lives and dies with it.
 */
public enum ProfileKind {

    TENANT,
    LDAP,
    SCIM,
    CSV,
    GOOGLE_WORKSPACE,
    ENTRA_ID;

    /** Whether a profile of this kind describes an identity source rather than the tenant itself. */
    public boolean isSource() {
        return this != TENANT;
    }
}

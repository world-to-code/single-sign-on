package com.example.sso.metadata;

/**
 * What a profile describes: the tenant's own people, or the shape of one identity source it reads from.
 *
 * <p>Every value except {@link #TENANT} describes a source. Most belong to a connector and die with it;
 * {@link #SCIM} does not — a SCIM client authenticates with a token, so its accountability is on the token
 * rather than on a connector row. {@link SourceConfigurators} is where that difference is expressed.
 */
public enum ProfileKind {

    TENANT,
    LDAP,
    SCIM,
    /** No ingestion path yet — a CSV import writes through an administrator, not a standing source. When one
     *  lands it must claim {@link SourceConfigurators}, or its attributes will be unattributable. */
    CSV,
    GOOGLE_WORKSPACE,
    ENTRA_ID;

    /** Whether a profile of this kind describes an identity source rather than the tenant itself. */
    public boolean isSource() {
        return this != TENANT;
    }
}

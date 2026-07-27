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
    ENTRA_ID,
    /** Upstream OIDC logins. Connector-less and ONE per tenant (like {@link #SCIM}): every OIDC provider a
     *  tenant registers feeds this single source, so a claim maps once instead of colliding per provider.
     *  Accountability is per-tenant — the {@code federation} module's {@link SourceConfigurators} answers with
     *  the administrators who configured the tenant's providers. */
    OIDC,
    /** Upstream SAML logins. Connector-less and ONE per tenant, like {@link #OIDC} — but a SEPARATE source,
     *  because SAML attribute names are chosen by the upstream IdP rather than by a specification. Sharing the
     *  OIDC source would let a connection name its attributes to match that source's mappings and write values
     *  recorded under a provenance it did not earn. Seeded with no mappings for the same reason: there is no
     *  standard name to guess, so the tenant declares what its upstream actually sends. */
    SAML;

    /** Whether a profile of this kind describes an identity source rather than the tenant itself. */
    public boolean isSource() {
        return this != TENANT;
    }
}

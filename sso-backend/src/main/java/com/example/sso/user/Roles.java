package com.example.sso.user;

/**
 * The platform's well-known role names (protocol constants), so they are referenced instead of
 * hardcoded as string literals across modules. Custom roles are admin-defined and not listed here.
 * A role name is emitted verbatim as a granted authority (see {@code SsoUserDetailsService}); with
 * Spring's {@code hasRole(...)} the {@code ROLE_} prefix is implicit, so prefer
 * {@code hasAuthority(Roles.ADMIN)} to keep the full name explicit.
 */
public final class Roles {

    /** Common prefix of every role authority (Spring's role marker); roles surface as authorities verbatim. */
    public static final String ROLE_PREFIX = "ROLE_";

    /** Super administrator: unscoped, self-heals to the full permission catalog. */
    public static final String ADMIN = "ROLE_ADMIN";
    /** Customer (고객사) administrator: manages every organization (branch) under the customers they administer. */
    public static final String CUSTOMER_ADMIN = "ROLE_CUSTOMER_ADMIN";
    /** Scoped delegated administrator: manages only the members of groups they manage. */
    public static final String GROUP_ADMIN = "ROLE_GROUP_ADMIN";
    /** Organization (tenant) administrator: manages only their own org's members (scoped in a later phase). */
    public static final String ORG_ADMIN = "ROLE_ORG_ADMIN";
    /** The baseline role every user holds. */
    public static final String USER = "ROLE_USER";
    /** The SCIM provisioning client's authority. */
    public static final String SCIM = "ROLE_SCIM";

    private Roles() {
    }
}

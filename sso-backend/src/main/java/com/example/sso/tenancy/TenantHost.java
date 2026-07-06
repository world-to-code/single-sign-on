package com.example.sso.tenancy;

/**
 * The tenancy labels parsed from a request Host. A single-label host {@code {org}.base} yields
 * {@code (orgSlug, null)} — the established per-tenant host (backward compatible). A two-label host
 * {@code {branch}.{customer}.base} yields {@code (branchSlug, customerSlug)} — a branch (organization)
 * addressed within its parent customer (고객사). The caller resolves these labels against the registries.
 */
public record TenantHost(String orgSlug, String customerSlug) {

    /** Whether this is a two-label {@code {branch}.{customer}.base} host (vs. a single-label {@code {org}.base}). */
    public boolean hasCustomer() {
        return customerSlug != null;
    }
}

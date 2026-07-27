package com.example.sso.federation;

import java.util.Map;
import java.util.UUID;

/**
 * Writes a federated login's verified id_token claims onto the local user, through the tenant's OIDC profile
 * mappings — the login-time twin of the SCIM/directory attribute sync. Called by the auth layer after it has
 * resolved (or provisioned) the account, since the write is keyed on the local user id.
 *
 * <p>Only claims a mapping says the OIDC source feeds are written, and only through the directory-owned path,
 * so a login can fill only what the tenant declared it may. Best-effort and non-fatal: a missing source,
 * profile or definition leaves the account authenticated with nothing filled.
 */
public interface FederationClaimSync {

    /** Apply the given claims (keyed by claim name) for {@code userId} in {@code orgId}. Runs in that tenant's context. */
    void applyClaims(UUID orgId, FederationProtocol protocol, String userId, Map<String, String> claims);
}

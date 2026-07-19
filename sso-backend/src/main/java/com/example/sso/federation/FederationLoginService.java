package com.example.sso.federation;

import java.util.List;
import java.util.UUID;

/**
 * Drives an inbound OIDC login against a tenant's configured upstream provider: lists the providers to offer,
 * builds the authorization redirect, and validates the callback into a {@link FederatedIdentity}. All OIDC
 * security (discovery, PKCE, id_token signature/issuer/audience/expiry/nonce validation, SSRF checks on every
 * upstream endpoint) lives behind this interface; the client secret never leaves the module. The
 * implementation stays module-internal.
 */
public interface FederationLoginService {

    /** The tenant's ENABLED providers for the login screen (org-scoped; no configuration or secret). */
    List<FederationProvider> enabledProviders(UUID orgId);

    /**
     * Begins a login through the tenant's {@code alias} provider: discovers the upstream, mints state/nonce/PKCE,
     * and returns where to redirect plus the values to stash. Rejects an unknown or disabled provider.
     */
    FederationAuthorization beginLogin(UUID orgId, String alias, String redirectUri);

    /**
     * Completes the callback: exchanges {@code code} at the token endpoint (with the PKCE {@code codeVerifier})
     * and validates the returned id_token (signature via the upstream JWKS, issuer, audience, expiry, and the
     * {@code nonce}). Returns the verified identity, or throws on any validation failure.
     */
    FederatedIdentity completeLogin(UUID orgId, String alias, String code, String redirectUri, String nonce,
                                    String codeVerifier);
}

package com.example.sso.federation;

import java.util.UUID;

/**
 * A verified SAML login. Carries the ORG because the ACS cannot learn it any other way — the cross-site POST
 * has no session cookie, so the tenant comes from the correlation record the login's start wrote, not from the
 * request.
 */
public record SamlLoginResult(UUID orgId, FederatedIdentity identity) {
}

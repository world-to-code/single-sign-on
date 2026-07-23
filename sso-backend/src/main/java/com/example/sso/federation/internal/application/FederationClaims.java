package com.example.sso.federation.internal.application;

import java.util.List;

/**
 * The OIDC-spec profile claims a federated login carries onto a user's attributes — the single source of truth
 * for both the {@link FederationSourceSeeder} (which declares them as attribute definitions and maps them) and
 * the {@code IdTokenVerifier} (which extracts them from the id_token), so the two cannot drift. Fixed by the
 * protocol, not by a vendor; a vendor's extra claims are the administrator's to add.
 */
final class FederationClaims {

    static final List<FederationClaim> STANDARD = List.of(
            new FederationClaim("given_name", "First name"),
            new FederationClaim("family_name", "Last name"),
            new FederationClaim("picture", "Picture URL"));

    private FederationClaims() {
    }
}

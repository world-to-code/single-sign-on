package com.example.sso.federation;

/**
 * The verified identity an upstream OIDC provider asserted for a user: the provider {@code alias}, the stable
 * {@code subject} (the {@code sub} claim), the {@code email} and whether the upstream marked it verified, a
 * display {@code name} (may be null), and the provider's {@code jitProvisioningAllowed} policy (carried here so
 * the auth layer can decide link-vs-provision-vs-deny without re-reading the config). Returned only after the
 * id_token's signature, issuer, audience, expiry and nonce have all been validated. {@code emailVerified} is
 * load-bearing: an account is linked/provisioned by email only when the upstream proved control of it.
 */
public record FederatedIdentity(String alias, String subject, String email, boolean emailVerified, String name,
                                boolean jitProvisioningAllowed) {
}

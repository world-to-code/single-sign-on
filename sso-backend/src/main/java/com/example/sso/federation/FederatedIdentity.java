package com.example.sso.federation;

import java.util.Map;

/**
 * The verified identity an upstream OIDC provider asserted for a user: the provider {@code alias} and the
 * {@code issuer} that actually minted it, the stable {@code subject} (the {@code sub} claim), the {@code email}
 * and whether the upstream marked it verified, a display {@code name} (may be null), the provider's
 * {@code jitProvisioningAllowed} policy (carried here so the auth layer can decide link-vs-provision-vs-deny
 * without re-reading the config), and the standard profile {@code claims} (keyed by claim name) the login can
 * carry onto the user's attributes. Returned only after the id_token's signature, issuer, audience, expiry and
 * nonce have all been validated.
 *
 * <p>{@code issuer}, not {@code alias}, identifies the upstream: an alias is a tenant-chosen label that can be
 * repointed at a different IdP, so a durable identity link keyed on the alias would silently carry over to the
 * new upstream. {@code emailVerified} is load-bearing wherever an account is matched BY email.
 *
 * <p>{@code addressAssertedByConfiguration} says the tenant deliberately accepted an address the upstream did
 * NOT verify as the name for a new account — because they named the attribute it comes from. SAML sets it (its
 * assertions never carry a verification); OIDC never does, because there the address arrives on a spec-fixed
 * claim alongside the upstream's own {@code email_verified} and no administrator ever decided to trust it
 * unproven. It gates PROVISIONING only, never matching.
 */
public record FederatedIdentity(String alias, String issuer, String subject, String email, boolean emailVerified,
                                String name, boolean jitProvisioningAllowed, boolean linkByVerifiedEmail,
                                boolean addressAssertedByConfiguration, Map<String, String> claims) {
}

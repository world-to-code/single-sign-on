package com.example.sso.saml.internal.sso.application;

/**
 * The per-user data carried in a SAML assertion: the {@code email} (used as the NameID and an attribute),
 * the optional {@code displayName}, and the optional {@code org} — the organization (tenant) id the session
 * logged into, mirroring the OIDC {@code org} claim so a relying party can scope the user to its tenant.
 * {@code null} display name / org are simply omitted from the AttributeStatement.
 */
public record AssertionSubject(String email, String displayName, String org) {
}

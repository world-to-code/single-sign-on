package com.example.sso.federation.internal.application;

import java.util.Map;

/**
 * The claims extracted from a fully-validated id_token: the stable subject, email (+verified), name, and the
 * standard profile {@code claims} ({@link FederationClaims}) keyed by claim name that a login carries onto the
 * user's attributes. Only non-blank claims are present.
 */
record VerifiedIdToken(String subject, String email, boolean emailVerified, String name,
                       Map<String, String> claims) {
}

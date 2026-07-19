package com.example.sso.federation.internal.application;

/** The claims extracted from a fully-validated id_token: the stable subject, email (+verified), and name. */
record VerifiedIdToken(String subject, String email, boolean emailVerified, String name) {
}

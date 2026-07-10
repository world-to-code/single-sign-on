package com.example.sso.admin.internal.signingkey.api;

/** Result of a SAML signing-key rotation: the new credential's key id. */
public record SamlSigningKeyRotationResponse(String keyId) {
}

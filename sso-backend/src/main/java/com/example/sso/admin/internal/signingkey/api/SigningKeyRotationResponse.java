package com.example.sso.admin.internal.signingkey.api;

/** Result of an OIDC signing-key rotation: the kid now signing new tokens. */
public record SigningKeyRotationResponse(String activeKid) {
}

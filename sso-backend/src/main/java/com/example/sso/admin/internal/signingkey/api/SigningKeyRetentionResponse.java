package com.example.sso.admin.internal.signingkey.api;

/** The acting tier's JWKS retention: how many rotated-away signing keys stay published. */
public record SigningKeyRetentionResponse(int retainedInactiveKeys) {
}

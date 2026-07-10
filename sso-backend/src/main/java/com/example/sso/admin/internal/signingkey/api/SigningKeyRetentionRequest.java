package com.example.sso.admin.internal.signingkey.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Update of the acting tier's JWKS retention: how many rotated-away signing keys stay published. */
public record SigningKeyRetentionRequest(@NotNull @Min(0) Integer retainedInactiveKeys) {
}

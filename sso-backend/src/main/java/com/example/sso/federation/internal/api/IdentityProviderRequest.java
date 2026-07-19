package com.example.sso.federation.internal.api;

import com.example.sso.federation.IdentityProviderSpec;
import jakarta.validation.constraints.NotBlank;

/**
 * Registers/updates an upstream OIDC provider for the acting tenant. {@code clientSecret} is WRITE-ONLY (never
 * echoed back; blank on update keeps the stored one). The {@code alias} comes from the path. Alias/issuer/scope
 * normalization + SSRF checks are enforced in the service — bean validation only bounds the shape.
 *
 * <p>{@code linkByVerifiedEmail} is BOXED on purpose. Jackson rejects a null for a primitive, so a plain
 * {@code boolean} would 400 a client that predates the field; boxed, an absent value reads as {@code false} —
 * address-based account matching can only be switched on by asking for it explicitly.
 */
public record IdentityProviderRequest(@NotBlank String displayName, @NotBlank String issuerUri,
                                      @NotBlank String clientId, String clientSecret, String scopes,
                                      boolean allowJitProvisioning, Boolean linkByVerifiedEmail,
                                      boolean enabled) {

    public IdentityProviderSpec toSpec(String alias) {
        return new IdentityProviderSpec(alias, displayName, issuerUri, clientId, clientSecret, scopes,
                allowJitProvisioning, Boolean.TRUE.equals(linkByVerifiedEmail), enabled);
    }
}

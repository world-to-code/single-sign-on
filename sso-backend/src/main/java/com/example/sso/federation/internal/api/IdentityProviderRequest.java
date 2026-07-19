package com.example.sso.federation.internal.api;

import com.example.sso.federation.IdentityProviderSpec;
import jakarta.validation.constraints.NotBlank;

/**
 * Registers/updates an upstream OIDC provider for the acting tenant. {@code clientSecret} is WRITE-ONLY (never
 * echoed back; blank on update keeps the stored one). The {@code alias} comes from the path. Alias/issuer/scope
 * normalization + SSRF checks are enforced in the service — bean validation only bounds the shape.
 */
public record IdentityProviderRequest(@NotBlank String displayName, @NotBlank String issuerUri,
                                      @NotBlank String clientId, String clientSecret, String scopes,
                                      boolean allowJitProvisioning, boolean enabled) {

    public IdentityProviderSpec toSpec(String alias) {
        return new IdentityProviderSpec(alias, displayName, issuerUri, clientId, clientSecret, scopes,
                allowJitProvisioning, enabled);
    }
}

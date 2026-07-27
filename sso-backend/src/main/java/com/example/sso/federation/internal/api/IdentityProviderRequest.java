package com.example.sso.federation.internal.api;

import com.example.sso.federation.FederationProtocol;
import com.example.sso.federation.IdentityProviderSpec;
import jakarta.validation.constraints.NotBlank;

/**
 * Registers/updates an upstream provider for the acting tenant. {@code clientSecret} is WRITE-ONLY (never echoed
 * back; blank on update keeps the stored one). The {@code alias} comes from the path. Normalization, SSRF checks
 * and the per-protocol required fields are enforced in the service — bean validation only bounds what is
 * unconditional, because which of {@code issuerUri}/{@code idpEntityId} is required depends on the protocol and
 * a field-level annotation cannot express that.
 *
 * <p>{@code linkByVerifiedEmail} is BOXED on purpose. Jackson rejects a null for a primitive, so a plain
 * {@code boolean} would 400 a client that predates the field; boxed, an absent value reads as {@code false} —
 * address-based account matching can only be switched on by asking for it explicitly. {@code protocol} is
 * absent-tolerant for the same reason and defaults to OIDC, the only protocol that existed before it.
 */
public record IdentityProviderRequest(@NotBlank String displayName, FederationProtocol protocol,
                                      String issuerUri, String clientId, String clientSecret, String scopes,
                                      String idpEntityId, String ssoUrl, String signingCertificate,
                                      String nameIdFormat, boolean allowJitProvisioning,
                                      Boolean linkByVerifiedEmail, boolean enabled, String presetId) {

    public IdentityProviderSpec toSpec(String alias) {
        boolean linkByEmail = Boolean.TRUE.equals(linkByVerifiedEmail);
        if (protocol == FederationProtocol.SAML) {
            return IdentityProviderSpec.saml(alias, displayName, idpEntityId, ssoUrl, signingCertificate,
                    nameIdFormat, allowJitProvisioning, linkByEmail, enabled, presetId);
        }
        return IdentityProviderSpec.oidc(alias, displayName, issuerUri, clientId, clientSecret, scopes,
                allowJitProvisioning, linkByEmail, enabled, presetId);
    }
}

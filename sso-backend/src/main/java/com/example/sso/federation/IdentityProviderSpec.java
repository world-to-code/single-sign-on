package com.example.sso.federation;

/**
 * Validated write input for registering/updating an upstream provider. {@code alias} identifies the provider
 * within the acting tier (the upsert key) and is immutable once created; {@code config} carries whatever the
 * chosen protocol needs, so the shared half stays free of fields that only apply to one of them.
 */
public record IdentityProviderSpec(String alias, String displayName, boolean allowJitProvisioning,
                                   boolean linkByVerifiedEmail, boolean enabled, String presetId,
                                   ProviderConfig config) {

    public FederationProtocol protocol() {
        return config.protocol();
    }

    /** An OIDC provider tagged with the preset (vendor) it was created from, or {@code null} for a custom one. */
    public static IdentityProviderSpec oidc(String alias, String displayName, String issuerUri, String clientId,
            String clientSecret, String scopes, boolean allowJitProvisioning, boolean linkByVerifiedEmail,
            boolean enabled, String presetId) {
        return new IdentityProviderSpec(alias, displayName, allowJitProvisioning, linkByVerifiedEmail, enabled,
                presetId, new OidcConfig(issuerUri, clientId, clientSecret, scopes));
    }

    /** A custom (preset-less) OIDC provider. */
    public static IdentityProviderSpec oidc(String alias, String displayName, String issuerUri, String clientId,
            String clientSecret, String scopes, boolean allowJitProvisioning, boolean linkByVerifiedEmail,
            boolean enabled) {
        return oidc(alias, displayName, issuerUri, clientId, clientSecret, scopes, allowJitProvisioning,
                linkByVerifiedEmail, enabled, null);
    }

    /** A SAML provider; {@code nameIdFormat} is optional (null requests nothing of the upstream). */
    public static IdentityProviderSpec saml(String alias, String displayName, String idpEntityId, String ssoUrl,
            String signingCertificate, String nameIdFormat, boolean allowJitProvisioning,
            boolean linkByVerifiedEmail, boolean enabled, String presetId) {
        return new IdentityProviderSpec(alias, displayName, allowJitProvisioning, linkByVerifiedEmail, enabled,
                presetId, new SamlConfig(idpEntityId, ssoUrl, signingCertificate, nameIdFormat));
    }
}

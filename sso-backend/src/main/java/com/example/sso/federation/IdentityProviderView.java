package com.example.sso.federation;

/**
 * An upstream provider for the admin config surface — NEVER the client secret (write-only). {@code alias} keys
 * the login route. The protocol-specific fields are flat and null for the other protocol: this is a wire DTO the
 * console branches on {@code protocol} to render, and a JSON union would buy type safety only on the server,
 * where {@link ProviderConfig} already provides it. The SAML signing certificate IS returned — it is a public
 * key an administrator must be able to read back to verify what the connection trusts.
 */
public record IdentityProviderView(String alias, String displayName, FederationProtocol protocol,
                                   String issuerUri, String clientId, String scopes,
                                   String idpEntityId, String ssoUrl, String signingCertificate,
                                   String nameIdFormat, String emailAttribute, boolean allowJitProvisioning,
                                   boolean linkByVerifiedEmail, boolean enabled, String presetId) {
}

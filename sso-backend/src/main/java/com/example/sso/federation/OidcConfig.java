package com.example.sso.federation;

/**
 * OIDC connection settings. {@code clientSecret} is PLAINTEXT on the write path (the service encrypts it before
 * it reaches the DB) and WRITE-ONLY — a blank value on an update KEEPS the stored ciphertext, so editing any
 * other field cannot wipe a secret the console never received back.
 */
public record OidcConfig(String issuerUri, String clientId, String clientSecret, String scopes)
        implements ProviderConfig {

    @Override
    public FederationProtocol protocol() {
        return FederationProtocol.OIDC;
    }
}

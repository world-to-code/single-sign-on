package com.example.sso.federation;

/**
 * SAML connection settings for an upstream IdP this product federates TO as the SP (distinct from the
 * relying-party registry, which is the IdP role this product plays for downstream SPs).
 *
 * <p>{@code idpEntityId} is the upstream's EntityID and doubles as the issuer a NameID is resolved in, so it is
 * the identity namespace — changing it retires the identities the old upstream minted. {@code signingCertificate}
 * is a PEM X.509 certificate, PINNED: an assertion is accepted only if it verifies against this key, rather than
 * against anything a metadata document happens to advertise. {@code nameIdFormat} is optional (null requests
 * nothing and accepts whatever the upstream sends).
 */
public record SamlConfig(String idpEntityId, String ssoUrl, String signingCertificate, String nameIdFormat)
        implements ProviderConfig {

    @Override
    public FederationProtocol protocol() {
        return FederationProtocol.SAML;
    }
}

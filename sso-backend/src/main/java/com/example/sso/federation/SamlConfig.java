package com.example.sso.federation;

/**
 * SAML connection settings for an upstream IdP this product federates TO as the SP (distinct from the
 * relying-party registry, which is the IdP role this product plays for downstream SPs).
 *
 * <p>{@code idpEntityId} is the upstream's EntityID and doubles as the issuer a NameID is resolved in, so it is
 * the identity namespace — changing it retires the identities the old upstream minted. {@code signingCertificate}
 * is a PEM X.509 certificate, PINNED: an assertion is accepted only if it verifies against this key, rather than
 * against anything a metadata document happens to advertise. {@code nameIdFormat} is required and restricted to
 * {@code persistent} — the only format stable enough to key a link on.
 *
 * <p>{@code emailAttribute} names the assertion attribute carrying the user's address, and exists because SAML
 * — unlike OIDC, whose claim names the spec fixes — lets the upstream choose its own. It is REQUIRED when
 * just-in-time provisioning is on (there would be nothing to name the new account with) and otherwise optional.
 * The address it yields is never treated as verified: an upstream asserting an address is not proof it verified
 * one, which is why it can name a NEW account but can never MATCH an existing one.
 */
public record SamlConfig(String idpEntityId, String ssoUrl, String signingCertificate, String nameIdFormat,
                         String emailAttribute) implements ProviderConfig {

    @Override
    public FederationProtocol protocol() {
        return FederationProtocol.SAML;
    }
}

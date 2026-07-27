package com.example.sso.saml.inbound;

/**
 * The upstream IdP this product federates to, as the SP needs to know it. {@code signingCertificate} is a PEM
 * X.509 certificate and is PINNED — an assertion is accepted only if it verifies against this key, never against
 * whatever a metadata document happens to advertise.
 */
public record UpstreamIdp(String entityId, String ssoUrl, String signingCertificate, String nameIdFormat) {
}

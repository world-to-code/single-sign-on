package com.example.sso.saml;

/** Admin view of a SAML relying party + its full per-RP security configuration. */
public record RelyingPartyView(String id, String entityId, String acsUrl, String nameIdFormat,
                               boolean signAssertion, boolean signResponse, boolean encryptAssertion,
                               String signatureAlgorithm, String dataEncryptionAlgorithm,
                               String keyTransportAlgorithm, boolean wantAuthnRequestsSigned,
                               boolean allowIdpInitiated, String signingCertificate,
                               String encryptionCertificate, String spLoginUrl) {
}

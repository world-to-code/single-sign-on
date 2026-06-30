package com.example.sso.saml;

import jakarta.validation.constraints.NotBlank;

/**
 * Admin create/update request for a SAML relying party. entityId is the immutable key (ignored on
 * update). Algorithm fields accept modern or legacy symbolic names (see {@link SamlSecuritySettings});
 * blank values fall back to secure defaults. Certificates are PEM (blank = none).
 */
public record RelyingPartyRequest(@NotBlank String entityId, @NotBlank String acsUrl, String nameIdFormat,
                                  boolean signAssertion, boolean signResponse, boolean encryptAssertion,
                                  String signatureAlgorithm, String dataEncryptionAlgorithm,
                                  String keyTransportAlgorithm, boolean wantAuthnRequestsSigned,
                                  boolean allowIdpInitiated, String signingCertificate,
                                  String encryptionCertificate) {
}

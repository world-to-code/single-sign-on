package com.example.sso.saml.internal.domain;

/**
 * Per-relying-party SAML security knobs. Algorithm fields are symbolic names resolved to
 * OpenSAML/XMLSec URIs at signing/encryption time (modern defaults; legacy values supported for
 * old SPs): signatureAlgorithm RSA_SHA256|RSA_SHA1|RSA_SHA512; dataEncryptionAlgorithm
 * AES256_GCM|AES128_GCM|AES256_CBC|AES128_CBC; keyTransportAlgorithm RSA_OAEP|RSA_OAEP_SHA256|RSA_1_5.
 */
public record SamlSecuritySettings(boolean signAssertion, boolean signResponse, boolean encryptAssertion,
                                   String signatureAlgorithm, String dataEncryptionAlgorithm,
                                   String keyTransportAlgorithm, boolean wantAuthnRequestsSigned,
                                   boolean allowIdpInitiated) {
}

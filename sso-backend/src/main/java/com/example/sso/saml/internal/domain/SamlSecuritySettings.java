package com.example.sso.saml.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Per-relying-party SAML security knobs, as an embeddable value object. Algorithm fields are symbolic
 * names resolved to OpenSAML/XMLSec URIs at signing/encryption time (modern defaults; legacy values
 * supported for old SPs): signatureAlgorithm RSA_SHA256|RSA_SHA1|RSA_SHA512; dataEncryptionAlgorithm
 * AES256_GCM|AES128_GCM|AES256_CBC|AES128_CBC; keyTransportAlgorithm RSA_OAEP|RSA_OAEP_SHA256|RSA_1_5.
 */
@Embeddable
public record SamlSecuritySettings(
        @Column(name = "sign_assertion", nullable = false) boolean signAssertion,
        @Column(name = "sign_response", nullable = false) boolean signResponse,
        @Column(name = "encrypt_assertion", nullable = false) boolean encryptAssertion,
        @Column(name = "signature_algorithm", nullable = false, length = 64) String signatureAlgorithm,
        @Column(name = "data_encryption_algorithm", nullable = false, length = 32) String dataEncryptionAlgorithm,
        @Column(name = "key_transport_algorithm", nullable = false, length = 32) String keyTransportAlgorithm,
        @Column(name = "want_authn_requests_signed", nullable = false) boolean wantAuthnRequestsSigned,
        @Column(name = "allow_idp_initiated", nullable = false) boolean allowIdpInitiated) {

    /** The default policy applied to a newly-registered SP. */
    public static SamlSecuritySettings defaults() {
        return new SamlSecuritySettings(true, false, false, "RSA_SHA256", "AES256_GCM", "RSA_OAEP", false, true);
    }
}

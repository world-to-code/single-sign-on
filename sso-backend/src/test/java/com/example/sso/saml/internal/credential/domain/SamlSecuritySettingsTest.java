package com.example.sso.saml.internal.credential.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the {@link SamlSecuritySettings} default policy applied to a newly-registered SP.
 * Pure value object, so asserted on its accessors. Guards the secure defaults: sign the assertion,
 * modern signature/encryption algorithms, no response signing, no assertion encryption.
 */
class SamlSecuritySettingsTest {

    @Test
    void defaultsAreSecureAndModern() {
        SamlSecuritySettings defaults = SamlSecuritySettings.defaults();

        assertThat(defaults.signAssertion()).isTrue();
        assertThat(defaults.signResponse()).isFalse();
        assertThat(defaults.encryptAssertion()).isFalse();
        assertThat(defaults.signatureAlgorithm()).isEqualTo("RSA_SHA256");
        assertThat(defaults.dataEncryptionAlgorithm()).isEqualTo("AES256_GCM");
        assertThat(defaults.keyTransportAlgorithm()).isEqualTo("RSA_OAEP");
        assertThat(defaults.wantAuthnRequestsSigned()).isFalse();
        assertThat(defaults.allowIdpInitiated()).isTrue();
    }
}

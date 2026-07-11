package com.example.sso.saml.internal.application;

import com.example.sso.saml.credential.SamlCredentialService;
import org.junit.jupiter.api.Test;
import org.opensaml.xmlsec.signature.support.SignatureConstants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The signature-algorithm-name → XMLSec URI switch shared by SSO and SLO signing: only the explicit legacy
 * name selects a legacy URI; every other/blank/null value falls through to the modern RSA-SHA256 default.
 */
class SamlSignerTest {

    private final SamlSigner signer = new SamlSigner(mock(SamlCredentialService.class));

    @Test
    void defaultsToRsaSha256WhenNull() {
        assertThat(signer.signatureUri(null)).isEqualTo(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
    }

    @Test
    void resolvesRsaSha1LegacyUri() {
        assertThat(signer.signatureUri("RSA_SHA1")).isEqualTo(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA1);
    }

    @Test
    void resolvesRsaSha512Uri() {
        assertThat(signer.signatureUri("RSA_SHA512")).isEqualTo(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA512);
    }

    @Test
    void fallsThroughToRsaSha256ForUnknownName() {
        assertThat(signer.signatureUri("bogus")).isEqualTo(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
    }
}

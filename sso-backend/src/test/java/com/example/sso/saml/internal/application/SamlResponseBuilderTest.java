package com.example.sso.saml.internal.application;

import com.example.sso.saml.SamlCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensaml.xmlsec.encryption.support.EncryptionConstants;
import org.opensaml.xmlsec.signature.support.SignatureConstants;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the pure algorithm-name -> OpenSAML/XMLSec URI resolution switches in
 * {@link SamlResponseBuilder}. These are pure, table-driven functions with no OpenSAML state, so they
 * are asserted on their return value. They are {@code private} and the only public entry point
 * ({@code issueResponse}) marshalls via the OpenSAML registry (needs a full OpenSAML bootstrap and real
 * credentials, i.e. not a fast unit test), so the switches are exercised directly through reflection.
 * The security-critical invariant under test: only the explicit legacy names select a legacy URI, and
 * every other/blank/null value falls through to the modern default.
 */
class SamlResponseBuilderTest {

    private SamlResponseBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SamlResponseBuilder(mock(SamlCredentialService.class), "https://idp.example/entity", 300L);
    }

    private String invoke(String methodName, String algorithm) throws Exception {
        Method method = SamlResponseBuilder.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (String) method.invoke(builder, algorithm);
    }

    // --- signatureUri: RSA_SHA256 default, RSA_SHA1 legacy, RSA_SHA512 explicit ---

    @Test
    void signatureDefaultsToRsaSha256WhenNull() throws Exception {
        assertThat(invoke("signatureUri", null)).isEqualTo(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
    }

    @Test
    void signatureResolvesRsaSha1LegacyUri() throws Exception {
        assertThat(invoke("signatureUri", "RSA_SHA1")).isEqualTo(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA1);
    }

    @Test
    void signatureResolvesRsaSha512Uri() throws Exception {
        assertThat(invoke("signatureUri", "RSA_SHA512")).isEqualTo(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA512);
    }

    @Test
    void signatureResolvesRsaSha256Uri() throws Exception {
        assertThat(invoke("signatureUri", "RSA_SHA256")).isEqualTo(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
    }

    @Test
    void signatureFallsThroughToRsaSha256ForUnknownName() throws Exception {
        assertThat(invoke("signatureUri", "bogus")).isEqualTo(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
    }

    // --- dataEncryptionUri: AES256_GCM default, GCM/CBC variants ---

    @Test
    void dataEncryptionDefaultsToAes256GcmWhenNull() throws Exception {
        assertThat(invoke("dataEncryptionUri", null))
                .isEqualTo(EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES256_GCM);
    }

    @Test
    void dataEncryptionResolvesAes128Gcm() throws Exception {
        assertThat(invoke("dataEncryptionUri", "AES128_GCM"))
                .isEqualTo(EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES128_GCM);
    }

    @Test
    void dataEncryptionResolvesAes256CbcLegacyUri() throws Exception {
        assertThat(invoke("dataEncryptionUri", "AES256_CBC"))
                .isEqualTo(EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES256);
    }

    @Test
    void dataEncryptionResolvesAes128CbcLegacyUri() throws Exception {
        assertThat(invoke("dataEncryptionUri", "AES128_CBC"))
                .isEqualTo(EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES128);
    }

    @Test
    void dataEncryptionFallsThroughToAes256GcmForUnknownName() throws Exception {
        assertThat(invoke("dataEncryptionUri", "AES999"))
                .isEqualTo(EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES256_GCM);
    }

    // --- keyTransportUri: ONLY RSA_1_5 is legacy; everything else -> RSA_OAEP ---

    @Test
    void keyTransportDefaultsToRsaOaepWhenNull() throws Exception {
        assertThat(invoke("keyTransportUri", null)).isEqualTo(EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSAOAEP);
    }

    @Test
    void keyTransportResolvesRsa15LegacyUri() throws Exception {
        assertThat(invoke("keyTransportUri", "RSA_1_5")).isEqualTo(EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSA15);
    }

    @Test
    void keyTransportResolvesRsaOaepForExplicitName() throws Exception {
        assertThat(invoke("keyTransportUri", "RSA_OAEP")).isEqualTo(EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSAOAEP);
    }

    @Test
    void keyTransportFallsThroughToRsaOaepForAnyOtherName() throws Exception {
        assertThat(invoke("keyTransportUri", "SOMETHING_ELSE"))
                .isEqualTo(EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSAOAEP);
    }
}

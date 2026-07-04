package com.example.sso.saml.internal.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensaml.xmlsec.encryption.support.EncryptionConstants;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the pure algorithm-name -> XMLSec encryption-URI resolution switches in
 * {@link SamlResponseBuilder}. These are pure, table-driven functions with no OpenSAML state; they are
 * {@code private}, so exercised through reflection. The security-critical invariant under test: only the
 * explicit legacy names select a legacy URI, and every other/blank/null value falls through to the modern
 * default. (Signature-algorithm resolution moved to {@link SamlSigner} — see {@code SamlSignerTest}.)
 */
class SamlResponseBuilderTest {

    private SamlResponseBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SamlResponseBuilder(mock(SamlSigner.class), "https://idp.example/entity", 300L);
    }

    private String invoke(String methodName, String algorithm) throws Exception {
        Method method = SamlResponseBuilder.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (String) method.invoke(builder, algorithm);
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

package com.example.sso.saml.internal.sso.application;

import com.example.sso.saml.internal.credential.application.SamlSigner;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;

import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensaml.core.config.InitializationService;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.xmlsec.encryption.support.EncryptionConstants;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the pure algorithm-name -> XMLSec encryption-URI resolution switches in
 * {@link SamlResponseBuilder}. These are pure, table-driven functions with no OpenSAML state; they are
 * {@code private}, so exercised through reflection. The security-critical invariant under test: only the
 * explicit legacy names select a legacy URI, and every other/blank/null value falls through to the modern
 * default. (Signature-algorithm resolution moved to {@link SamlSigner} — see {@code SamlSignerTest}.)
 */
class SamlResponseBuilderTest {

    private SamlResponseBuilder builder;

    @BeforeAll
    static void bootstrapOpenSaml() throws Exception {
        InitializationService.initialize(); // idempotent; registers the OpenSAML builders the assertion uses
    }

    @BeforeEach
    void setUp() {
        builder = new SamlResponseBuilder(mock(SamlSigner.class), 300L);
    }

    /** A relying party with no signing/encryption so issueResponse yields an inspectable, unsigned assertion. */
    private SamlRelyingParty plainSp() {
        SamlRelyingParty sp = mock(SamlRelyingParty.class);
        when(sp.getNameIdFormat()).thenReturn(NameID.EMAIL);
        when(sp.getAcsUrl()).thenReturn("https://sp.example/acs");
        when(sp.getEntityId()).thenReturn("urn:example:sp");
        return sp; // isSignAssertion / isEncryptAssertion / isSignResponse default to false on the mock
    }

    private List<String> attributeNames(Response response) {
        Assertion assertion = response.getAssertions().get(0);
        return assertion.getAttributeStatements().get(0).getAttributes().stream()
                .map(Attribute::getName).toList();
    }

    @Test
    void issueResponseEmitsTheOrgAttributeSymmetricWithOidc() {
        AssertionSubject subject = new AssertionSubject("u@x.io", "User", "11111111-1111-1111-1111-111111111111");

        Response response = builder.issueResponse(plainSp(), "req-1", subject, "sid-1", "https://idp.example");

        assertThat(attributeNames(response)).contains("email", "displayName", "org");
    }

    @Test
    void issueResponseOmitsTheOrgAttributeForAGlobalSession() {
        AssertionSubject subject = new AssertionSubject("root@x.io", "Root", null); // a global (org-less) session

        Response response = builder.issueResponse(plainSp(), "req-2", subject, "sid-2", "https://idp.example");

        assertThat(attributeNames(response)).contains("email", "displayName").doesNotContain("org");
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

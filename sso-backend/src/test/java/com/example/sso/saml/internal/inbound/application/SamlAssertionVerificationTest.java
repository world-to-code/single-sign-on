package com.example.sso.saml.internal.inbound.application;

import com.example.sso.saml.inbound.AssertionExpectations;
import com.example.sso.saml.inbound.VerifiedAssertion;
import com.example.sso.saml.internal.core.application.SamlBindingCodec;
import com.example.sso.saml.internal.core.application.SamlObjects;
import com.example.sso.shared.error.UnauthorizedException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import javax.security.auth.x500.X500Principal;
import net.shibboleth.shared.xml.SerializeSupport;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensaml.core.config.InitializationService;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.core.Advice;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.NameIDType;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.Signer;
import org.springframework.test.util.ReflectionTestUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rejection matrix of the inbound assertion verifier — the one place in this feature where getting it wrong
 * is an authentication bypass rather than an outage.
 *
 * <p>Each test builds a document that is VALID except for one thing, and signs it AFTER the change, so the
 * signature is always genuine and the only reason a test can go red is the check it names. A test that mutated
 * the XML after signing would prove nothing but "the signature broke".
 */
class SamlAssertionVerificationTest {

    private static final String IDP_ENTITY_ID = "https://idp.corp.example/entity";
    private static final String SP_ENTITY_ID = "https://acme.idp.example/saml2/sp/corp";
    private static final String ACS_URL = "https://acme.idp.example/api/auth/federation/corp/acs";
    private static final String REQUEST_ID = "_e1e2b0e0-0000-4000-8000-000000000001";
    private static final String NAME_ID = "persistent-opaque-subject-42";
    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");
    // Aliased rather than used inline: the library names push the DOM calls below past 120 columns.
    private static final String SAML_NS = SAMLConstants.SAML20_NS;
    private static final String SIGNATURE_NS = SignatureConstants.XMLSIG_NS;

    private static KeyPair idpKeys;
    private static X509Certificate idpCertificate;
    private static KeyPair attackerKeys;
    private static X509Certificate attackerCertificate;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private SamlSpProtocolImpl verifier;

    @BeforeAll
    static void bootstrap() throws Exception {
        InitializationService.initialize();
        idpKeys = rsaKeyPair();
        idpCertificate = selfSigned(idpKeys, "CN=idp.corp.example");
        attackerKeys = rsaKeyPair();
        attackerCertificate = selfSigned(attackerKeys, "CN=attacker.example");
    }

    private SamlSpProtocolImpl verifier() {
        if (verifier == null) {
            SamlBindingCodec codec = new SamlBindingCodec(
                    XMLObjectProviderRegistrySupport.getParserPool(), 262_144);
            verifier = new SamlSpProtocolImpl(codec, null, null, clock);
            ReflectionTestUtils.setField(verifier, "clockSkew", Duration.ofMinutes(1));
        }
        return verifier;
    }

    private AssertionExpectations expectations() {
        return new AssertionExpectations(IDP_ENTITY_ID, pem(idpCertificate), SP_ENTITY_ID, ACS_URL, REQUEST_ID,
                NameIDType.PERSISTENT);
    }

    // --- the happy path, so every rejection below is provably about its own check ---------------------

    @Test
    void aWellFormedAssertionIsAccepted() {
        VerifiedAssertion verified = verifier().verify(signedResponse(response -> { }), expectations());

        assertThat(verified.nameId()).isEqualTo(NAME_ID);
        assertThat(verified.assertionId()).isNotBlank();
    }

    // --- who signed it -------------------------------------------------------------------------------

    @Test
    void anAssertionSignedByAnotherKeyIsRefused() {
        // The pinned certificate is the whole trust decision: anything else can mint XML.
        String document = signedResponse(response -> { }, attackerKeys, attackerCertificate);

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anUnsignedAssertionIsRefused() {
        String document = encode(buildResponse(response -> { })); // never signed

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aResponseCarryingMoreThanOneAssertionIsRefused() {
        // Not a wrapping test — onlyAssertion refuses on the COUNT before any signature work. Kept because it
        // is what kills the "allow multiple assertions" mutant; the real wrapping fixture is below.
        String document = signedResponse(response -> response.getAssertions().add(assertion(a -> {
            a.getSubject().getNameID().setValue("smuggled-subject");
            a.setID("_smuggled");
        })));

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anAssertionWearingAnotherAssertionsSignatureIsRefused() {
        // THE signature-wrapping attack. The Response carries exactly ONE assertion, so the count check does
        // not fire; that assertion claims the victim and is unsigned, but wears the ds:Signature lifted from a
        // genuine assertion the attacker obtained for themselves, relocated into its <Advice> so the Reference
        // URI still resolves. A naive verifier resolves the reference to the RELOCATED bytes, finds them
        // genuinely signed by the pinned key, and then reads the victim's NameID from an element it never
        // verified.
        //
        // WHICH layer refuses it, measured rather than assumed: deleting SAMLSignatureProfileValidator leaves
        // this test GREEN, so what actually catches this shape is Santuario's own secureValidation
        // (protectAgainstWrappingAttack on the fragment resolver). The profile validator is defence in depth
        // for the reference tricks Santuario does not cover — multiple References, a foreign Transform, a
        // ds:Object child — and NOTHING here pins it. Do not read this test as covering it.
        String document = wrappedResponse();

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    // --- who it is from, who it is for, where it was sent --------------------------------------------

    @Test
    void anAssertionFromAnotherIssuerIsRefused() {
        String document = signedResponse(response ->
                response.getAssertions().get(0).getIssuer().setValue("https://someone-else.example/entity"));

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anAssertionAudiencedToAnotherServiceProviderIsRefused() {
        // Otherwise an assertion the same upstream minted for a DIFFERENT SP could be replayed here.
        String document = signedResponse(response -> audienceOf(response).setURI("https://other-sp.example"));

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anAssertionWithNoAudienceRestrictionIsRefused() {
        // An assertion for nobody in particular is an assertion for anybody.
        String document = signedResponse(response ->
                response.getAssertions().get(0).getConditions().getAudienceRestrictions().clear());

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aResponseSentToAnotherDestinationIsRefused() {
        String document = signedResponse(response -> response.setDestination("https://evil.example/acs"));

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aBearerConfirmationForAnotherRecipientIsRefused() {
        String document = signedResponse(response ->
                confirmationOf(response).setRecipient("https://evil.example/acs"));

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    // --- that it answers a request WE made -----------------------------------------------------------

    @Test
    void anUnsolicitedAssertionIsRefused() {
        // No InResponseTo means the attacker, not the victim, asked for it.
        String document = signedResponse(response -> confirmationOf(response).setInResponseTo(null));

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anAssertionAnsweringADifferentRequestIsRefused() {
        String document = signedResponse(response -> confirmationOf(response).setInResponseTo("_some-other-id"));

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    // --- when it is valid ----------------------------------------------------------------------------

    @Test
    void anExpiredAssertionIsRefused() {
        String document = signedResponse(response ->
                response.getAssertions().get(0).getConditions().setNotOnOrAfter(NOW.minusSeconds(120)));

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anAssertionNotYetValidIsRefused() {
        String document = signedResponse(response ->
                response.getAssertions().get(0).getConditions().setNotBefore(NOW.plusSeconds(120)));

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void clockSkewIsToleratedAtBothEnds() {
        // A minute of drift is normal between two organizations' clocks; refusing it would be an outage, and
        // tolerating an hour would be a replay window.
        assertThatCode(() -> verifier().verify(signedResponse(response -> {
            response.getAssertions().get(0).getConditions().setNotBefore(NOW.plusSeconds(30));
            response.getAssertions().get(0).getConditions().setNotOnOrAfter(NOW.minusSeconds(30));
        }), expectations())).doesNotThrowAnyException();
    }

    @Test
    void anExpiredBearerConfirmationIsRefused() {
        String document = signedResponse(response ->
                confirmationOf(response).setNotOnOrAfter(NOW.minusSeconds(120)));

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    // --- the status, and the join key ----------------------------------------------------------------

    @Test
    void aNonSuccessStatusIsRefused() {
        String document = signedResponse(response ->
                response.getStatus().getStatusCode().setValue(StatusCode.AUTHN_FAILED));

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aNameIdInAnotherFormatIsRefused() {
        // The connection is configured for a stable identifier. An upstream that starts sending an address
        // instead would silently change what the link is keyed on — a reassignable value.
        String document = signedResponse(response ->
                response.getAssertions().get(0).getSubject().getNameID().setFormat(NameIDType.EMAIL));

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anEmptyNameIdIsRefused() {
        String document = signedResponse(response ->
                response.getAssertions().get(0).getSubject().getNameID().setValue("  "));

        assertThatThrownBy(() -> verifier().verify(document, expectations()))
                .isInstanceOf(UnauthorizedException.class);
    }

    // --- fixtures ------------------------------------------------------------------------------------

    private AudienceRestriction audienceRestrictionOf(Response response) {
        return response.getAssertions().get(0).getConditions().getAudienceRestrictions().get(0);
    }

    private Audience audienceOf(Response response) {
        return audienceRestrictionOf(response).getAudiences().get(0);
    }

    private SubjectConfirmationData confirmationOf(Response response) {
        return response.getAssertions().get(0).getSubject().getSubjectConfirmations().get(0)
                .getSubjectConfirmationData();
    }

    private String signedResponse(Consumer<Response> tweak) {
        return signedResponse(tweak, idpKeys, idpCertificate);
    }

    /** Applies the tweak, THEN signs — so the signature is genuine and each test isolates its own check. */
    private String signedResponse(Consumer<Response> tweak, KeyPair keys, X509Certificate certificate) {
        Response response = buildResponse(tweak);
        try {
            for (Assertion assertion : response.getAssertions()) {
                Signature signature = SamlObjects.build(Signature.DEFAULT_ELEMENT_NAME);
                signature.setSigningCredential(new BasicX509Credential(certificate, keys.getPrivate()));
                signature.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
                signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
                assertion.setSignature(signature);
                XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(assertion)
                        .marshall(assertion);
                Signer.signObject(signature);
            }
            return encode(response);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign the test assertion", e);
        }
    }

    /**
     * Builds the wrapping document by DOM surgery, because it cannot be expressed through the object model:
     * sign a genuine assertion for the ATTACKER, then hand its signature to a second, unsigned assertion that
     * claims the VICTIM, hiding the signed original inside that assertion's Advice so the signature's
     * Reference URI still resolves to it.
     */
    private String wrappedResponse() {
        try {
            Assertion genuine = assertion(a -> {
                a.setID("_genuine");
                a.getSubject().getNameID().setValue("attacker-own-subject");
            });
            Signature signature = SamlObjects.build(Signature.DEFAULT_ELEMENT_NAME);
            signature.setSigningCredential(new BasicX509Credential(idpCertificate, idpKeys.getPrivate()));
            signature.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
            signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
            genuine.setSignature(signature);
            XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(genuine).marshall(genuine);
            Signer.signObject(signature);
            Element genuineDom = genuine.getDOM();

            Response response = buildResponse(r -> { });
            response.getAssertions().get(0).setID("_victim-claim");
            Element responseDom = XMLObjectProviderRegistrySupport.getMarshallerFactory()
                    .getMarshaller(response).marshall(response);
            Document doc = responseDom.getOwnerDocument();

            Element victim = (Element) responseDom.getElementsByTagNameNS(SAML_NS, Assertion.DEFAULT_ELEMENT_LOCAL_NAME).item(0);
            Element lifted = (Element) genuineDom.getElementsByTagNameNS(SIGNATURE_NS, Signature.DEFAULT_ELEMENT_LOCAL_NAME).item(0);
            Element advice = doc.createElementNS(SAML_NS,
                    SAMLConstants.SAML20_PREFIX + ":" + Advice.DEFAULT_ELEMENT_LOCAL_NAME);
            advice.appendChild(doc.importNode(genuineDom, true));

            // The signature becomes the victim-claiming assertion's own child, and the element it actually
            // covers is tucked inside that assertion's Advice.
            victim.insertBefore(doc.importNode(lifted, true), victim.getFirstChild().getNextSibling());
            victim.appendChild(advice);

            return Base64.getEncoder().encodeToString(SerializeSupport.nodeToString(responseDom).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build the wrapping fixture", e);
        }
    }

    private String encode(Response response) {
        try {
            Element element = XMLObjectProviderRegistrySupport.getMarshallerFactory()
                    .getMarshaller(response).marshall(response);
            return Base64.getEncoder().encodeToString(SerializeSupport.nodeToString(element).getBytes());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode the test response", e);
        }
    }

    private Response buildResponse(Consumer<Response> tweak) {
        Response response = SamlObjects.build(Response.DEFAULT_ELEMENT_NAME);
        response.setID("_response-1");
        response.setVersion(SAMLVersion.VERSION_20);
        response.setIssueInstant(NOW);
        response.setDestination(ACS_URL);
        response.setInResponseTo(REQUEST_ID);

        StatusCode code = SamlObjects.build(StatusCode.DEFAULT_ELEMENT_NAME);
        code.setValue(StatusCode.SUCCESS);
        Status status = SamlObjects.build(Status.DEFAULT_ELEMENT_NAME);
        status.setStatusCode(code);
        response.setStatus(status);

        response.getAssertions().add(assertion(a -> { }));
        tweak.accept(response);
        return response;
    }

    private Assertion assertion(Consumer<Assertion> tweak) {
        Assertion assertion = SamlObjects.build(Assertion.DEFAULT_ELEMENT_NAME);
        assertion.setID("_assertion-1");
        assertion.setVersion(SAMLVersion.VERSION_20);
        assertion.setIssueInstant(NOW);

        Issuer issuer = SamlObjects.build(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue(IDP_ENTITY_ID);
        assertion.setIssuer(issuer);

        NameID nameId = SamlObjects.build(NameID.DEFAULT_ELEMENT_NAME);
        nameId.setValue(NAME_ID);
        nameId.setFormat(NameIDType.PERSISTENT);

        SubjectConfirmationData data = SamlObjects.build(SubjectConfirmationData.DEFAULT_ELEMENT_NAME);
        data.setRecipient(ACS_URL);
        data.setInResponseTo(REQUEST_ID);
        data.setNotOnOrAfter(NOW.plusSeconds(300));

        SubjectConfirmation confirmation = SamlObjects.build(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
        confirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
        confirmation.setSubjectConfirmationData(data);

        Subject subject = SamlObjects.build(Subject.DEFAULT_ELEMENT_NAME);
        subject.setNameID(nameId);
        subject.getSubjectConfirmations().add(confirmation);
        assertion.setSubject(subject);

        Audience audience = SamlObjects.build(Audience.DEFAULT_ELEMENT_NAME);
        audience.setURI(SP_ENTITY_ID);
        AudienceRestriction restriction = SamlObjects.build(AudienceRestriction.DEFAULT_ELEMENT_NAME);
        restriction.getAudiences().add(audience);
        Conditions conditions = SamlObjects.build(Conditions.DEFAULT_ELEMENT_NAME);
        conditions.setNotBefore(NOW.minusSeconds(60));
        conditions.setNotOnOrAfter(NOW.plusSeconds(300));
        conditions.getAudienceRestrictions().add(restriction);
        assertion.setConditions(conditions);

        tweak.accept(assertion);
        return assertion;
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate selfSigned(KeyPair keys, String dn) throws Exception {
        X500Principal subject = new X500Principal(dn);
        Date from = Date.from(NOW.minusSeconds(86_400));
        Date to = Date.from(NOW.plusSeconds(86_400 * 365));
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.ONE, from, to, subject, keys.getPublic());
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
    }

    private static String pem(X509Certificate certificate) {
        try {
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes())
                    .encodeToString(certificate.getEncoded());
            return String.join("\n", List.of("-----BEGIN CERTIFICATE-----", base64, "-----END CERTIFICATE-----"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

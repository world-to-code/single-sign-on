package com.example.sso.saml.internal.inbound.application;

import com.example.sso.saml.inbound.SamlAuthnRequest;
import com.example.sso.saml.inbound.UpstreamIdp;
import com.example.sso.saml.internal.core.application.SamlBindingCodec;
import com.example.sso.saml.internal.core.application.SamlRedirectEncoder;
import com.example.sso.saml.internal.credential.application.SamlSigner;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensaml.core.config.InitializationService;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.NameIDType;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The outbound half of the SP protocol, decoded back from the wire rather than inspected in memory — so a field
 * that never made it into the encoded message cannot pass.
 *
 * <p>Both ends of one contract live here: the request id this returns is what the caller stores, and it is what
 * the ACS later demands in {@code InResponseTo}. If the id in the message and the id handed back ever diverged,
 * every login would be refused with a matching pair of green tests either side of the gap.
 */
class SamlAuthnRequestBuildTest {

    private static final String SSO_URL = "https://sts.corp.example/sso";
    private static final String SP_ENTITY_ID = "https://acme.idp.example/saml2/sp/corp";
    private static final String ACS_URL = "https://acme.idp.example/api/auth/federation/corp/acs";
    private static final int MAX_INFLATED_BYTES = 262_144; // sso.saml.max-inflated-bytes

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-27T10:00:00Z"), ZoneOffset.UTC);
    private final SamlSigner signer = mock(SamlSigner.class);
    private SamlSpProtocolImpl protocol;

    @BeforeAll
    static void bootstrap() throws Exception {
        InitializationService.initialize();
    }

    private SamlSpProtocolImpl protocol() throws Exception {
        if (protocol == null) {
            // A real marshaller, a stub signature: the detached Redirect-binding signature is SamlSigner's
            // contract and is covered there; what this test is about is the message that gets signed.
            when(signer.signQueryString(any(), any())).thenReturn(new byte[] { 1, 2, 3 });
            when(signer.signatureUri(any())).thenReturn(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
            doAnswer(invocation -> {
                XMLObject target = invocation.getArgument(0);
                XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(target).marshall(target);
                return null;
            }).when(signer).marshall(any());
            SamlBindingCodec codec = new SamlBindingCodec(
                    XMLObjectProviderRegistrySupport.getParserPool(), MAX_INFLATED_BYTES);
            protocol = new SamlSpProtocolImpl(codec, signer, new SamlRedirectEncoder(signer), clock);
            ReflectionTestUtils.setField(protocol, "signatureAlgorithm", "RSA_SHA256");
        }
        return protocol;
    }

    private UpstreamIdp upstream() {
        return new UpstreamIdp("https://sts.corp.example/entity", SSO_URL, "cert", NameIDType.PERSISTENT);
    }

    private Map<String, String> queryOf(String url) {
        return Arrays.stream(url.substring(url.indexOf('?') + 1).split("&"))
                .map(pair -> pair.split("=", 2))
                // '+' is a real base64 character here, not the form-encoding's space — URLDecoder would
                // eat it and leave an undecodable SAMLRequest.
                .collect(Collectors.toMap(p -> p[0],
                        p -> URLDecoder.decode(p[1].replace("+", "%2B"), StandardCharsets.UTF_8)));
    }

    private AuthnRequest decoded(SamlAuthnRequest built) throws Exception {
        SamlBindingCodec codec = new SamlBindingCodec(
                XMLObjectProviderRegistrySupport.getParserPool(), MAX_INFLATED_BYTES);
        return codec.decodeRedirect(queryOf(built.redirectUrl()).get("SAMLRequest"));
    }

    @Test
    void theRequestNamesUsAsTheIssuerAndTheAcsAsWhereToAnswer() throws Exception {
        // Four adjacent URLs in the builder; a swap between any pair is a connection the upstream refuses or,
        // worse, one that posts its assertion somewhere else.
        AuthnRequest request = decoded(protocol().buildAuthnRequest(upstream(), SP_ENTITY_ID, ACS_URL, "rs"));

        assertThat(request.getIssuer().getValue()).isEqualTo(SP_ENTITY_ID);
        assertThat(request.getAssertionConsumerServiceURL()).isEqualTo(ACS_URL);
        assertThat(request.getDestination()).isEqualTo(SSO_URL);
        assertThat(request.getProtocolBinding()).isEqualTo(SAMLConstants.SAML2_POST_BINDING_URI);
    }

    @Test
    void theRequestAsksForTheFormatTheConnectionRequires() throws Exception {
        // The ACS enforces the format too; asking is the courtesy that stops an upstream sending what we will
        // then refuse.
        AuthnRequest request = decoded(protocol().buildAuthnRequest(upstream(), SP_ENTITY_ID, ACS_URL, "rs"));

        assertThat(request.getNameIDPolicy().getFormat()).isEqualTo(NameIDType.PERSISTENT);
    }

    @Test
    void theReturnedRequestIdIsTheOneInTheMessage() throws Exception {
        // The contract the ACS depends on: the caller stores this id and the assertion must echo it. A
        // divergence here is invisible to both sides' own tests.
        SamlAuthnRequest built = protocol().buildAuthnRequest(upstream(), SP_ENTITY_ID, ACS_URL, "rs");

        assertThat(decoded(built).getID()).isEqualTo(built.requestId());
    }

    @Test
    void theRequestIdIsALegalXmlIdentifier() throws Exception {
        // xsd:ID may not start with a digit, and a raw UUID does about 60% of the time — an upstream that
        // schema-validates would reject those and nothing else here would notice.
        SamlAuthnRequest built = protocol().buildAuthnRequest(upstream(), SP_ENTITY_ID, ACS_URL, "rs");

        assertThat(built.requestId()).startsWith("_");
    }

    @Test
    void theRelayStateRidesAlongSoTheAcsCanCorrelate() throws Exception {
        SamlAuthnRequest built = protocol().buildAuthnRequest(upstream(), SP_ENTITY_ID, ACS_URL, "relay-1");

        assertThat(queryOf(built.redirectUrl())).containsEntry("RelayState", "relay-1");
        assertThat(built.redirectUrl()).startsWith(SSO_URL + "?");
    }

    @Test
    void eachRequestGetsItsOwnId() throws Exception {
        // Two logins sharing an id would let one's assertion complete the other's correlation record.
        SamlAuthnRequest first = protocol().buildAuthnRequest(upstream(), SP_ENTITY_ID, ACS_URL, "rs");
        SamlAuthnRequest second = protocol().buildAuthnRequest(upstream(), SP_ENTITY_ID, ACS_URL, "rs");

        assertThat(first.requestId()).isNotEqualTo(second.requestId());
    }
}

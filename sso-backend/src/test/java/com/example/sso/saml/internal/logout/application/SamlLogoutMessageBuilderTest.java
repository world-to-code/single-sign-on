package com.example.sso.saml.internal.logout.application;

import com.example.sso.saml.internal.core.application.SamlEntityId;
import com.example.sso.saml.internal.credential.application.SamlSigner;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;

import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensaml.core.config.InitializationService;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.MarshallingException;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.core.LogoutResponse;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.StatusCode;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The Issuer and Status of the SLO messages this IdP emits.
 *
 * <p>Issuer must be the RP's OWN tenant entityID — the one its SSO assertions carried and the one it
 * registered — because the message is signed with that tenant's key. A platform Issuer on a tenant-signed
 * message is an SP-side mismatch, and it is not visible from {@code SamlEntityId}'s own tests.
 *
 * <p>Status must be honest: {@code PartialLogout} when nothing was terminated, so an SP never records a
 * completed global logout while the IdP session lives on.
 */
@ExtendWith(MockitoExtension.class)
class SamlLogoutMessageBuilderTest {

    private static final String PLATFORM_ENTITY_ID = "http://localhost:9000/saml2/idp";
    private static final String TENANT_ENTITY_ID = "http://acme.localhost:9000/saml2/idp";

    @Mock
    private SamlSigner signer;
    @Mock
    private SamlEntityId entityId;

    private SamlLogoutMessageBuilder builder;

    @BeforeEach
    void setUp() throws Exception {
        InitializationService.initialize();
        // The signer is stubbed to only MARSHALL (produce the DOM) — signing needs a real credential and is
        // irrelevant to the Issuer/Status contract under test.
        lenient().doAnswer(invocation -> {
            marshall(invocation.getArgument(0));
            return null;
        }).when(signer).marshall(any());
        builder = new SamlLogoutMessageBuilder(signer, entityId);
    }

    private void marshall(XMLObject object) throws MarshallingException {
        XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(object).marshall(object);
    }

    private SamlRelyingParty rp(UUID orgId) {
        return new SamlRelyingParty("https://sp.example", "https://sp.example/acs", NameID.EMAIL, orgId);
    }

    private SamlRelyingParty tenantRp() {
        UUID orgId = UUID.randomUUID();
        lenient().when(entityId.forOrg(orgId)).thenReturn(TENANT_ENTITY_ID);
        return rp(orgId);
    }

    @Test
    void aLogoutRequestToATenantSpCarriesThatTenantsEntityId() throws Exception {
        // The real marshaller runs (the signer is a no-op mock), so this asserts the emitted XML, not a field.
        String xml = builder.unsignedLogoutRequestXml(tenantRp(), "user@example.com", "sid-1");

        assertThat(issuerOf(xml)).isEqualTo(TENANT_ENTITY_ID);
    }

    @Test
    void aLogoutRequestToAGlobalSpCarriesThePlatformEntityId() throws Exception {
        when(entityId.forOrg(null)).thenReturn(PLATFORM_ENTITY_ID);

        String xml = builder.unsignedLogoutRequestXml(rp(null), "user@example.com", "sid-1");

        assertThat(issuerOf(xml)).isEqualTo(PLATFORM_ENTITY_ID);
    }

    /** The {@code <Issuer>} text of a serialized SAML message. */
    private String issuerOf(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true); // else getElementsByTagNameNS never matches
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        return document.getElementsByTagNameNS(SAMLConstants.SAML20_NS, "Issuer").item(0).getTextContent();
    }

    @Test
    void aLogoutResponseCarriesTheSameTenantEntityIdAsTheAssertionsDid() {
        LogoutResponse response = builder.signedLogoutResponse(tenantRp(), "req-1", true);

        assertThat(response.getIssuer().getValue()).isEqualTo(TENANT_ENTITY_ID);
    }

    @Test
    void aTerminatedSessionAnswersSuccess() {
        LogoutResponse response = builder.signedLogoutResponse(tenantRp(), "req-1", true);

        assertThat(response.getStatus().getStatusCode().getValue()).isEqualTo(StatusCode.SUCCESS);
        assertThat(response.getStatus().getStatusCode().getStatusCode()).isNull();
    }

    @Test
    void terminatingNothingAnswersPartialLogout() {
        LogoutResponse response = builder.signedLogoutResponse(tenantRp(), "req-1", false);

        assertThat(response.getStatus().getStatusCode().getValue()).isEqualTo(StatusCode.RESPONDER);
        assertThat(response.getStatus().getStatusCode().getStatusCode().getValue())
                .isEqualTo(StatusCode.PARTIAL_LOGOUT);
    }
}

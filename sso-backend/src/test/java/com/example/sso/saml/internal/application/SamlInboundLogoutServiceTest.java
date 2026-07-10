package com.example.sso.saml.internal.application;

import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.Factors;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensaml.core.config.InitializationService;
import org.opensaml.core.xml.XMLObjectBuilderFactory;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.LogoutResponse;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.SessionIndex;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * An SP-initiated {@code LogoutRequest} names WHICH session it wants ended, via {@code SessionIndex} (the OP
 * session id stamped into the assertion). Terminating whatever session the browser happens to carry — without
 * matching that index — logs the user out of the wrong session when they hold several. These tests pin the
 * match: the ambient session dies only when the request targets it.
 */
@ExtendWith(MockitoExtension.class)
class SamlInboundLogoutServiceTest {

    private static final String SID = "sid-current";

    @Mock
    private SamlLogoutMessageBuilder messageBuilder;
    @Mock
    private SamlBindingCodec codec;
    @Mock
    private AuditService audit;

    private SamlInboundLogoutService service;
    private XMLObjectBuilderFactory builders;

    @BeforeEach
    void setUp() throws Exception {
        InitializationService.initialize();
        builders = XMLObjectProviderRegistrySupport.getBuilderFactory();
        service = new SamlInboundLogoutService(messageBuilder, codec, audit);
        lenient().when(messageBuilder.signedLogoutResponse(any(), anyString()))
                .thenReturn(build(LogoutResponse.DEFAULT_ELEMENT_NAME));
        lenient().when(codec.encodeObject(any())).thenReturn("b64");
    }

    @SuppressWarnings("unchecked")
    private <T> T build(javax.xml.namespace.QName name) {
        return (T) builders.getBuilder(name).buildObject(name);
    }

    private LogoutRequest logoutRequest(String sessionIndex) {
        LogoutRequest request = build(LogoutRequest.DEFAULT_ELEMENT_NAME);
        request.setID("req-1");
        NameID nameId = build(NameID.DEFAULT_ELEMENT_NAME);
        nameId.setValue("user@example.com");
        request.setNameID(nameId);
        if (sessionIndex != null) {
            SessionIndex index = build(SessionIndex.DEFAULT_ELEMENT_NAME);
            index.setValue(sessionIndex);
            request.getSessionIndexes().add(index);
        }
        return request;
    }

    private MockHttpServletRequest requestWithSession(MockHttpSession session) {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest("POST", "/saml2/idp/slo");
        httpRequest.setSession(session);
        return httpRequest;
    }

    private Authentication sessionPrincipal() {
        return new UsernamePasswordAuthenticationToken("user", null,
                List.of(new SimpleGrantedAuthority(Factors.SID_PREFIX + SID)));
    }

    private SamlRelyingParty rp() {
        return new SamlRelyingParty("https://sp.example", "https://sp.example/acs",
                NameID.EMAIL, UUID.randomUUID());
    }

    @Test
    void terminatesTheSessionTheRequestTargets() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest httpRequest = requestWithSession(session);

        service.process(logoutRequest(SID), rp(), "user", sessionPrincipal(), null, httpRequest);

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void leavesASessionTheRequestDoesNotTargetAlive() {
        // The SP asks to end a DIFFERENT session of the same subject (another browser). Killing the ambient
        // one would log the user out of a session no SP asked about.
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest httpRequest = requestWithSession(session);

        service.process(logoutRequest("sid-other"), rp(), "user", sessionPrincipal(), null, httpRequest);

        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void terminatesTheAmbientSessionWhenTheRequestNamesNoSessionIndex() {
        // SessionIndex is optional in SAML: with none, the request targets the subject's session at this IdP.
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest httpRequest = requestWithSession(session);

        service.process(logoutRequest(null), rp(), "user", sessionPrincipal(), null, httpRequest);

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void respondsWithoutASessionAtAll() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest("POST", "/saml2/idp/slo");

        SamlInboundLogoutService.InboundLogout result =
                service.process(logoutRequest(SID), rp(), "user", null, "relay", httpRequest);

        assertThat(result.base64Response()).isEqualTo("b64");
        assertThat(result.relayState()).isEqualTo("relay");
    }
}

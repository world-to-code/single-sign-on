package com.example.sso.saml.internal.logout.application;

import com.example.sso.saml.internal.core.application.SamlBindingCodec;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.saml.internal.logout.application.SamlInboundLogoutService.InboundResult;
import com.example.sso.saml.logout.SamlFrontChannelLogout;
import javax.xml.namespace.QName;
import java.util.List;
import java.util.Optional;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private SamlFrontChannelLogout frontChannel;
    @Mock
    private AuditService audit;

    private SamlInboundLogoutService service;
    private XMLObjectBuilderFactory builders;

    @BeforeEach
    void setUp() throws Exception {
        InitializationService.initialize();
        builders = XMLObjectProviderRegistrySupport.getBuilderFactory();
        service = new SamlInboundLogoutService(messageBuilder, codec, frontChannel, audit);
        lenient().when(messageBuilder.signedLogoutResponse(any(), anyString(), anyBoolean()))
                .thenReturn(build(LogoutResponse.DEFAULT_ELEMENT_NAME));
        lenient().when(codec.encodeObject(any())).thenReturn("b64");
        // By default no OTHER front-channel SP remains -> the initiator is answered immediately. Individual
        // tests override to stage a chain.
        lenient().when(frontChannel.startInboundChain(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    private MockHttpServletResponse resp() {
        return new MockHttpServletResponse();
    }

    @SuppressWarnings("unchecked")
    private <T> T build(QName name) {
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

        service.process(logoutRequest(SID), rp(), "user", sessionPrincipal(), null, httpRequest, resp());

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void leavesASessionTheRequestDoesNotTargetAlive() {
        // The SP asks to end a DIFFERENT session of the same subject (another browser). Killing the ambient
        // one would log the user out of a session no SP asked about.
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest httpRequest = requestWithSession(session);

        service.process(logoutRequest("sid-other"), rp(), "user", sessionPrincipal(), null, httpRequest, resp());

        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void terminatesTheAmbientSessionWhenTheRequestNamesNoSessionIndex() {
        // SessionIndex is optional in SAML: with none, the request targets the subject's session at this IdP.
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest httpRequest = requestWithSession(session);

        service.process(logoutRequest(null), rp(), "user", sessionPrincipal(), null, httpRequest, resp());

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void respondsWithoutASessionAtAll() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest("POST", "/saml2/idp/slo");

        InboundResult result = service.process(logoutRequest(SID), rp(), "user", null, "relay", httpRequest, resp());

        SamlInboundLogoutService.InboundLogout logout = respond(result);
        assertThat(logout.base64Response()).isEqualTo("b64");
        assertThat(logout.relayState()).isEqualTo("relay");
    }

    @Test
    void stagesTheFrontChannelChainForTheOtherSpsAndAnswersTheInitiatorLast() {
        // Another front-channel SP is still logged in: the browser is redirected through the chain (which ends
        // by answering the initiator), instead of the initiator getting its LogoutResponse immediately. The
        // initiator's OWN entityId is what the chain is told to EXCLUDE.
        MockHttpSession session = new MockHttpSession();
        when(frontChannel.startInboundChain(eq(SID), eq("https://sp.example"), eq("req-1"), any(), any()))
                .thenReturn(Optional.of("/saml2/idp/slo/chain?logout=x"));

        InboundResult result = service.process(logoutRequest(SID), rp(), "user", sessionPrincipal(), "relay",
                requestWithSession(session), resp());

        assertThat(result).isInstanceOf(InboundResult.Chain.class);
        assertThat(((InboundResult.Chain) result).redirectUrl()).isEqualTo("/saml2/idp/slo/chain?logout=x");
        assertThat(session.isInvalid()).isTrue(); // the session is still ended before the chain runs
    }

    @Test
    void aFrontChannelStagingFailureStillEndsTheSessionAndAnswersTheInitiator() {
        // Local logout is load-bearing: a Redis/DB fault while staging the chain must NOT leave the IdP session
        // (and its SSO to every other RP) alive. Degrade to answering the initiator directly, session still ended.
        MockHttpSession session = new MockHttpSession();
        when(frontChannel.startInboundChain(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));

        InboundResult result = service.process(logoutRequest(SID), rp(), "user", sessionPrincipal(), "relay",
                requestWithSession(session), resp());

        assertThat(session.isInvalid()).isTrue();
        assertThat(result).isInstanceOf(InboundResult.Respond.class); // not a 500, not a chain — answered directly
    }

    private SamlInboundLogoutService.InboundLogout respond(InboundResult result) {
        return ((InboundResult.Respond) result).logout();
    }

    @Test
    void reportsAPartialLogoutAndAuditsAFailureWhenNothingWasTerminated() {
        // The SP keeps a SessionIndex from an EARLIER IdP session (SP app-sessions outlive IdP sessions). If we
        // answered Success, the SP would record a completed global logout while the IdP session — and its SSO to
        // every other RP — lives on. Say so instead: partial logout, audited as a failure.
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest httpRequest = requestWithSession(session);

        InboundResult result = service.process(logoutRequest("sid-stale"), rp(), "user", sessionPrincipal(),
                "relay", httpRequest, resp());

        assertThat(session.isInvalid()).isFalse();
        verify(messageBuilder).signedLogoutResponse(any(), eq("req-1"), eq(false)); // terminated=false
        assertThat(respond(result).base64Response()).isEqualTo("b64"); // the SP still gets a signed response
        verify(audit).record(argThat(record -> !record.success()));
        // A request that ended no session must NOT redirect the browser through other SPs' logout.
        verify(frontChannel, never()).startInboundChain(any(), any(), any(), any(), any());
    }

    @Test
    void auditsASuccessWhenTheTargetedSessionIsTerminated() {
        MockHttpServletRequest httpRequest = requestWithSession(new MockHttpSession());

        service.process(logoutRequest(SID), rp(), "user", sessionPrincipal(), null, httpRequest, resp());

        verify(messageBuilder).signedLogoutResponse(any(), eq("req-1"), eq(true));
        verify(audit).record(argThat(AuditRecord::success));
    }

    @Test
    void terminatesWhenAnyOfSeveralSessionIndexesMatches() {
        // A LogoutRequest may name several sessions; ours is the second.
        MockHttpSession session = new MockHttpSession();
        LogoutRequest request = logoutRequest("sid-other");
        SessionIndex second = build(SessionIndex.DEFAULT_ELEMENT_NAME);
        second.setValue(SID);
        request.getSessionIndexes().add(second);

        service.process(request, rp(), "user", sessionPrincipal(), null, requestWithSession(session), resp());

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void leavesASessionWithoutASessionIdMarkerAlone() {
        // A session that never completed login carries no SID_ marker, so no SP holds a SessionIndex for it:
        // a targeted request can never name it, and must not fall through to killing it.
        MockHttpSession session = new MockHttpSession();
        Authentication markerless = new UsernamePasswordAuthenticationToken("user", null, List.of());

        service.process(logoutRequest(SID), rp(), "user", markerless, null, requestWithSession(session), resp());

        assertThat(session.isInvalid()).isFalse();
    }
}

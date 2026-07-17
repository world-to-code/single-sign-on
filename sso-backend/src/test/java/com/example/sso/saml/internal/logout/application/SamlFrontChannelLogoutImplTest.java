package com.example.sso.saml.internal.logout.application;

import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingPartyRepository;
import com.example.sso.saml.relyingparty.SloBinding;
import com.example.sso.tenancy.OrgContext;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Staging a front-channel logout chain. The SP-initiated variant must EXCLUDE the initiator from the chain (it
 * initiated the logout and is answered with a LogoutResponse when the chain drains) and record it as the
 * chain's responder; only OTHER front-channel (Redirect/POST) SPs are chained.
 */
@ExtendWith(MockitoExtension.class)
class SamlFrontChannelLogoutImplTest {

    @Mock
    SamlSloSessionIndex sloIndex;
    @Mock
    SamlRelyingPartyRepository relyingParties;
    @Mock
    SamlLogoutChainStore chainStore;
    @Mock
    SamlLogoutChainCookie chainCookie;
    @Mock
    OrgContext orgContext;
    @InjectMocks
    SamlFrontChannelLogoutImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(orgContext.callAsPlatform(any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
    }

    private SamlRelyingParty frontChannelRp() {
        SamlRelyingParty rp = mock(SamlRelyingParty.class);
        when(rp.getSingleLogoutUrl()).thenReturn("https://sp/slo");
        when(rp.sloBinding()).thenReturn(SloBinding.REDIRECT);
        return rp;
    }

    @Test
    void startInboundChainExcludesTheInitiatorAndRecordsItAsTheResponder() {
        SamlRelyingParty spB = frontChannelRp();
        when(sloIndex.lookup("sid")).thenReturn(Map.of("initiator", "nameA", "spB", "nameB"));
        when(relyingParties.findByEntityId("spB")).thenReturn(Optional.of(spB));

        Optional<String> url = service.startInboundChain("sid", "initiator", "req-1", "relay",
                new MockHttpServletResponse());

        assertThat(url).isPresent();
        verify(relyingParties, never()).findByEntityId("initiator"); // excluded before any RP lookup
        verify(chainStore).create(any(), eq("sid"),
                argThat(list -> list.size() == 1 && list.getFirst().entityId().equals("spB")),
                argThat(r -> r != null && r.entityId().equals("initiator") && r.requestId().equals("req-1")
                        && "relay".equals(r.relayState())));
        verify(chainCookie).issue(any(), any());
    }

    @Test
    void startInboundChainIsEmptyWhenOnlyTheInitiatorIsFrontChannel() {
        when(sloIndex.lookup("sid")).thenReturn(Map.of("initiator", "nameA"));

        Optional<String> url = service.startInboundChain("sid", "initiator", "req-1", "relay",
                new MockHttpServletResponse());

        assertThat(url).isEmpty();
        verify(chainStore, never()).create(any(), any(), any(), any());
        verify(chainCookie, never()).issue(any(), any());
    }

    @Test
    void startChainForExplicitLogoutIncludesEveryFrontChannelSpAndHasNoResponder() {
        SamlRelyingParty spA = frontChannelRp();
        when(sloIndex.lookup("sid")).thenReturn(Map.of("spA", "nameA"));
        when(relyingParties.findByEntityId("spA")).thenReturn(Optional.of(spA));

        assertThat(service.startChain("sid", new MockHttpServletResponse())).isPresent();

        verify(chainStore).create(any(), eq("sid"),
                argThat(list -> list.size() == 1 && list.getFirst().entityId().equals("spA")),
                isNull()); // browser-logout chain answers no initiator
    }
}

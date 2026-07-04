package com.example.sso.saml.internal.application;

import com.example.sso.saml.SloBinding;
import com.example.sso.saml.internal.application.SamlLogoutChainService.ChainStep;
import com.example.sso.saml.internal.application.SamlLogoutChainStore.Hop;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
import com.example.sso.saml.internal.domain.SamlRelyingPartyRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The chain service must pick the right browser step per SP binding and complete when the chain is empty. */
@ExtendWith(MockitoExtension.class)
class SamlLogoutChainServiceTest {

    @Mock
    SamlLogoutChainStore chainStore;
    @Mock
    SamlRelyingPartyRepository relyingParties;
    @Mock
    SamlLogoutMessageBuilder messageBuilder;
    @Mock
    SamlRedirectEncoder redirectEncoder;
    @Mock
    SamlBindingCodec codec;
    @InjectMocks
    SamlLogoutChainService service;

    private SamlRelyingParty rp(SloBinding binding) {
        SamlRelyingParty rp = mock(SamlRelyingParty.class);
        when(rp.getSingleLogoutUrl()).thenReturn("https://sp.example/slo");
        when(rp.sloBinding()).thenReturn(binding);
        return rp;
    }

    @Test
    void redirectBindingHopProducesARedirectStep() {
        SamlRelyingParty rp = rp(SloBinding.REDIRECT);
        when(chainStore.next("L")).thenReturn(Optional.of(new Hop("sp", "user@x", "sid-1")));
        when(relyingParties.findByEntityId("sp")).thenReturn(Optional.of(rp));
        when(messageBuilder.unsignedLogoutRequestXml(any(), eq("user@x"), eq("sid-1")))
                .thenReturn("<xml/>");
        when(redirectEncoder.encodeRequest(any(), any(), eq("L"), any()))
                .thenReturn("https://sp.example/slo?SAMLRequest=..&Signature=..");

        ChainStep step = service.next("L", "nonce");

        assertThat(step).isInstanceOf(ChainStep.Redirect.class);
    }

    @Test
    void postBindingHopProducesAPostFormStep() {
        SamlRelyingParty rp = rp(SloBinding.POST);
        when(chainStore.next("L")).thenReturn(Optional.of(new Hop("sp", "user@x", "sid-1")));
        when(relyingParties.findByEntityId("sp")).thenReturn(Optional.of(rp));
        when(messageBuilder.signedLogoutRequestXml(any(), eq("user@x"), eq("sid-1")))
                .thenReturn("<xml/>");
        when(codec.postRequestHtml(any(), any(), eq("L"), eq("nonce")))
                .thenReturn("<html/>");

        ChainStep step = service.next("L", "nonce");

        assertThat(step).isInstanceOf(ChainStep.PostForm.class);
    }

    @Test
    void exhaustedChainCompletesAndClears() {
        when(chainStore.next("L")).thenReturn(Optional.empty());

        ChainStep step = service.next("L", "nonce");

        assertThat(step).isInstanceOf(ChainStep.Complete.class);
        verify(chainStore).clear("L");
    }
}

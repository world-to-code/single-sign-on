package com.example.sso.saml.internal.logout.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.portal.application.AppSessionParticipation;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingPartyRepository;
import com.example.sso.saml.relyingparty.SloBinding;
import com.example.sso.shared.error.BadRequestException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the portal-facing SAML participant read + single-participant logout: SOAP SPs are one-click
 * capable, front-channel SPs are listed but their logout is refused (never touching the index), and a SOAP
 * logout delivers + removes only that one SP.
 */
@ExtendWith(MockitoExtension.class)
class SamlAppSessionSourceTest {

    private static final String SID = "sid-1";
    private static final String USER = "carol";

    @Mock
    SamlSloSessionIndex index;
    @Mock
    SamlRelyingPartyRepository relyingParties;
    @Mock
    SamlParticipantDelivery delivery;
    @Mock
    AuditService audit;

    private SamlAppSessionSource source;

    @BeforeEach
    void setUp() {
        source = new SamlAppSessionSource(index, relyingParties, delivery, audit);
    }

    private SamlRelyingParty rp(String display, String sloUrl, SloBinding binding) {
        SamlRelyingParty rp = mock(SamlRelyingParty.class);
        lenient().when(rp.getDisplayName()).thenReturn(display);
        lenient().when(rp.getSingleLogoutUrl()).thenReturn(sloUrl);
        lenient().when(rp.sloBinding()).thenReturn(binding);
        return rp;
    }

    @Test
    void participationsFlagSoapAsOneClickCapableAndFrontChannelAsNot() {
        SamlRelyingParty soap = rp("Payroll", "https://sp/slo", SloBinding.SOAP);
        SamlRelyingParty frontChannel = rp("Wiki", "https://sp/slo", SloBinding.REDIRECT);
        when(index.lookup(SID)).thenReturn(Map.of("sp-soap", "n1", "sp-fc", "n2"));
        when(relyingParties.findByEntityId("sp-soap")).thenReturn(Optional.of(soap));
        when(relyingParties.findByEntityId("sp-fc")).thenReturn(Optional.of(frontChannel));

        assertThat(source.participationsFor(Set.of(SID)))
                .extracting(AppSessionParticipation::appId, AppSessionParticipation::name,
                        AppSessionParticipation::oneClickLogoutSupported)
                .containsExactlyInAnyOrder(
                        tuple("sp-soap", "Payroll", true),
                        tuple("sp-fc", "Wiki", false));
    }

    @Test
    void logoutOfASoapSpDeliversAndRemovesOnlyThatSp() {
        when(index.lookup(SID)).thenReturn(Map.of("sp-soap", "nameid"));
        SamlRelyingParty soap = rp("Payroll", "https://sp/slo", SloBinding.SOAP);
        when(relyingParties.findByEntityId("sp-soap")).thenReturn(Optional.of(soap));
        when(delivery.sendSoap(soap, "nameid", SID)).thenReturn(true);

        source.logout(SID, "sp-soap", USER);

        verify(delivery).sendSoap(soap, "nameid", SID);
        verify(index).removeParticipants(SID, Set.of("sp-soap")); // only this SP; the session lives on
        verify(index, never()).clear(any());
        verify(audit).record(argThat(r -> r != null && r.success() && r.detail().contains("sp-soap")));
    }

    @Test
    void logoutOfATransientlyFailedSoapSpKeepsItInTheIndexForTheDurableBackstop() {
        when(index.lookup(SID)).thenReturn(Map.of("sp-soap", "nameid"));
        SamlRelyingParty soap = rp("Payroll", "https://sp/slo", SloBinding.SOAP);
        when(relyingParties.findByEntityId("sp-soap")).thenReturn(Optional.of(soap));
        when(delivery.sendSoap(soap, "nameid", SID)).thenReturn(false); // SP momentarily unreachable

        source.logout(SID, "sp-soap", USER);

        verify(index, never()).removeParticipants(any(), any()); // kept so a later whole-session SLO re-drives it
        verify(audit).record(argThat(r -> r != null && !r.success()));
    }

    @Test
    void participationsSkipAnRpRemovedSinceLogin() {
        when(index.lookup(SID)).thenReturn(Map.of("sp-gone", "nameid"));
        when(relyingParties.findByEntityId("sp-gone")).thenReturn(Optional.empty());

        assertThat(source.participationsFor(Set.of(SID))).isEmpty();
    }

    @Test
    void logoutOfAnSpRemovedSinceLoginIsRejected() {
        when(index.lookup(SID)).thenReturn(Map.of("sp-gone", "nameid")); // still held under the sid
        when(relyingParties.findByEntityId("sp-gone")).thenReturn(Optional.empty()); // but the RP is gone

        assertThatThrownBy(() -> source.logout(SID, "sp-gone", USER)).isInstanceOf(BadRequestException.class);
        verify(delivery, never()).sendSoap(any(), any(), any());
        verify(index, never()).removeParticipants(any(), any());
    }

    @Test
    void logoutOfAFrontChannelSpIsRefusedAndNeverTouchesTheIndex() {
        SamlRelyingParty frontChannel = rp("Wiki", "https://sp/slo", SloBinding.REDIRECT);
        when(index.lookup(SID)).thenReturn(Map.of("sp-fc", "nameid"));
        when(relyingParties.findByEntityId("sp-fc")).thenReturn(Optional.of(frontChannel));

        assertThatThrownBy(() -> source.logout(SID, "sp-fc", USER)).isInstanceOf(BadRequestException.class);

        verify(delivery, never()).sendSoap(any(), any(), any());
        verify(index, never()).removeParticipants(any(), any());
        verify(audit, never()).record(any(AuditRecord.class));
    }

    @Test
    void logoutOfAnSpNotHeldUnderTheSidIsANoOp() {
        when(index.lookup(SID)).thenReturn(Map.of()); // already logged out / never held

        source.logout(SID, "sp-soap", USER);

        verify(relyingParties, never()).findByEntityId(any());
        verify(delivery, never()).sendSoap(any(), any(), any());
        verify(index, never()).removeParticipants(any(), any());
    }
}

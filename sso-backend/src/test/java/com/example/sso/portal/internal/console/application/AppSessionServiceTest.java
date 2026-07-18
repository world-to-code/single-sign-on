package com.example.sso.portal.internal.console.application;

import com.example.sso.portal.application.AppSessionParticipation;
import com.example.sso.portal.application.AppSessionSource;
import com.example.sso.portal.application.AppType;
import com.example.sso.session.lifecycle.UserSessions;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the portal app-session aggregation + self-service per-app logout: merge/dedup across
 * sessions, and the IDOR guard — a user can only sign out an app THEIR OWN sessions hold, routed to the
 * owning source for every matching session and never a whole-session termination.
 */
@ExtendWith(MockitoExtension.class)
class AppSessionServiceTest {

    private static final String USER = "dave";

    @Mock
    UserSessions userSessions;
    @Mock
    OrgContext orgContext;
    @Mock
    AppSessionSource oidc;
    @Mock
    AppSessionSource saml;

    private AppSessionService service;

    @BeforeEach
    void setUp() {
        service = new AppSessionService(userSessions, orgContext, List.of(oidc, saml));
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty());
        lenient().when(oidc.type()).thenReturn(AppType.OIDC);
        lenient().when(saml.type()).thenReturn(AppType.SAML);
    }

    private AppSessionParticipation oidcApp(String clientId, String sid) {
        return new AppSessionParticipation(AppType.OIDC, clientId, sid, "app-" + clientId, true);
    }

    @Test
    void listMergesEverySourceAndCollapsesAnAppHeldAcrossSessions() {
        when(userSessions.activeSidsForUser(USER, null)).thenReturn(Set.of("s1", "s2"));
        // "billing" appears under BOTH sids — it must collapse to a single row.
        when(oidc.participationsFor(Set.of("s1", "s2")))
                .thenReturn(List.of(oidcApp("billing", "s1"), oidcApp("billing", "s2")));
        when(saml.participationsFor(Set.of("s1", "s2")))
                .thenReturn(List.of(new AppSessionParticipation(AppType.SAML, "sp-hr", "s1", "HR", true)));

        assertThat(service.list(USER))
                .extracting(AppSessionView::type, AppSessionView::appId)
                .containsExactlyInAnyOrder(
                        tuple("OIDC", "billing"),
                        tuple("SAML", "sp-hr"));
    }

    @Test
    void logoutOfAnAppTheUserDoesNotHoldIsRejectedWithoutDispatch() {
        when(userSessions.activeSidsForUser(USER, null)).thenReturn(Set.of("s1"));
        when(oidc.participationsFor(Set.of("s1"))).thenReturn(List.of(oidcApp("billing", "s1")));

        assertThatThrownBy(() -> service.logout(USER, AppType.OIDC, "intranet"))
                .isInstanceOf(NotFoundException.class);
        verify(oidc, never()).logout(any(), any(), any());
    }

    @Test
    void logoutRoutesToTheOwningSourceForEveryMatchingSession() {
        when(userSessions.activeSidsForUser(USER, null)).thenReturn(Set.of("s1", "s2"));
        when(oidc.participationsFor(Set.of("s1", "s2")))
                .thenReturn(List.of(oidcApp("billing", "s1"), oidcApp("billing", "s2"), oidcApp("wiki", "s1")));

        service.logout(USER, AppType.OIDC, "billing");

        verify(oidc).logout("s1", "billing", USER);
        verify(oidc).logout("s2", "billing", USER); // every session holding the app
        verify(oidc, never()).logout("s1", "wiki", USER); // never a different app
        verify(saml, never()).logout(any(), any(), any()); // never the other protocol
    }

    @Test
    void logoutOfANonOneClickCapableAppIsRefusedBeforeDispatch() {
        // A SAML front-channel-only SP (or an OIDC client with no back-channel URI) is listed but not one-click
        // capable. The server enforces this — a disabled client-side button is not enforcement (zero-trust).
        when(userSessions.activeSidsForUser(USER, null)).thenReturn(Set.of("s1"));
        when(saml.participationsFor(Set.of("s1")))
                .thenReturn(List.of(new AppSessionParticipation(AppType.SAML, "sp-fc", "s1", "Wiki", false)));

        assertThatThrownBy(() -> service.logout(USER, AppType.SAML, "sp-fc"))
                .isInstanceOf(BadRequestException.class);
        verify(saml, never()).logout(any(), any(), any());
    }

    @Test
    void oneSessionsLogoutFailingDoesNotAbortTheOthers() {
        // The app is held across two sessions; an IdP-side fault on the first must not skip the second.
        when(userSessions.activeSidsForUser(USER, null)).thenReturn(Set.of("s1", "s2"));
        when(oidc.participationsFor(Set.of("s1", "s2")))
                .thenReturn(List.of(oidcApp("billing", "s1"), oidcApp("billing", "s2")));
        doThrow(new IllegalStateException("blip")).when(oidc).logout("s1", "billing", USER);

        service.logout(USER, AppType.OIDC, "billing");

        verify(oidc).logout("s2", "billing", USER); // the second session is still processed
    }

    @Test
    void logoutForAnUnknownAppTypeIsRejected() {
        assertThatThrownBy(() -> service.logout(USER, AppType.PORTAL, "whatever"))
                .isInstanceOf(NotFoundException.class);
        verify(userSessions, never()).activeSidsForUser(any(), any());
    }
}

package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.session.lifecycle.SessionMetadata;
import com.example.sso.session.lifecycle.SessionMetadataStore;
import com.example.sso.session.policy.UserSessionPolicy;
import com.example.sso.shared.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.Session;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link SessionManagerImpl#registerAndEnforceLimit} — the concurrent-session cap that
 * our custom JSON login must enforce by hand (Spring's strategy does not run). The unit's job IS an
 * interaction (register the session, evict the overflow), so these tests {@code verify()}: over-limit
 * evicts the OLDEST sessions via hard delete, at/under-limit evicts nothing, and 0 means unlimited.
 * {@code terminateForUser} org-scoping and {@code revoke} are covered too. The access-change listeners
 * that used to live here now sit in {@link AccessChangeSessionTerminator} and are tested there.
 */
@ExtendWith(MockitoExtension.class)
class SessionManagerImplTest {

    private static final String USER = "alice";

    @Mock
    private SessionRegistry sessionRegistry;
    @Mock
    private SessionMetadataStore sessionMetadata;
    @Mock
    private UserSessionPolicy sessionPolicy;
    @Mock
    private FindByIndexNameSessionRepository<Session> sessionRepository;

    private SessionManagerImpl manager;

    @BeforeEach
    void setUp() {
        manager = new SessionManagerImpl(sessionRegistry, sessionMetadata, sessionPolicy, sessionRepository);
    }

    private MockHttpServletRequest requestWithSession() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.setSession(new MockHttpSession());
        request.setRemoteAddr("10.0.0.1");
        return request;
    }

    private void policyWithLimit(int max) {
        // The manager reads the composed FLOOR cap (min non-zero across governing policies), not a raw policy.
        when(sessionPolicy.maxConcurrentSessionsFor(USER)).thenReturn(max);
    }

    private SessionInformation session(String id, Instant lastRequest) {
        SessionInformation info = mock(SessionInformation.class);
        when(info.getLastRequest()).thenReturn(Date.from(lastRequest));
        return info;
    }

    @Test
    void noServletSessionShortCircuitsWithoutTouchingTheRegistry() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login"); // no session

        manager.registerAndEnforceLimit(request, USER);

        verify(sessionRegistry, never()).registerNewSession(anyString(), any());
        verify(sessionMetadata, never()).record(anyString(), anyString(), any(), any());
    }

    @Test
    void firstSightRecordsMetadataAndLeavesRegistrationToSpringSession() {
        MockHttpServletRequest request = requestWithSession();
        String id = request.getSession(false).getId();
        policyWithLimit(0); // unlimited → no eviction path

        manager.registerAndEnforceLimit(request, USER);

        // Spring Session auto-registers + principal-indexes the session; we only stamp device metadata.
        verify(sessionMetadata).record(eq(id), eq(USER), any(), eq("10.0.0.1"));
        verify(sessionRegistry, never()).registerNewSession(anyString(), any());
    }

    @Test
    void unlimitedPolicyNeverQueriesActiveSessions() {
        MockHttpServletRequest request = requestWithSession();
        policyWithLimit(0);

        manager.registerAndEnforceLimit(request, USER);

        verify(sessionRegistry, never()).getAllSessions(anyString(), anyBoolean());
    }

    @Test
    void atOrUnderTheLimitEvictsNothing() {
        MockHttpServletRequest request = requestWithSession();
        String currentId = request.getSession(false).getId();
        policyWithLimit(2);
        SessionInformation a = mock(SessionInformation.class);
        SessionInformation b = mock(SessionInformation.class);
        when(a.getSessionId()).thenReturn(currentId); // the registry already includes the current session
        when(sessionRegistry.getAllSessions(USER, false)).thenReturn(List.of(a, b));

        manager.registerAndEnforceLimit(request, USER);

        verify(sessionRepository, never()).deleteById(anyString());
    }

    @Test
    void overTheLimitEvictsTheOldestSessionsOnly() {
        MockHttpServletRequest request = requestWithSession();
        String currentId = request.getSession(false).getId();
        policyWithLimit(2);
        Instant now = Instant.now();
        SessionInformation oldest = session("s1", now.minusSeconds(300));
        SessionInformation middle = session("s2", now.minusSeconds(200));
        SessionInformation newest = session("s3", now.minusSeconds(100));
        when(oldest.getSessionId()).thenReturn("s1");
        when(newest.getSessionId()).thenReturn(currentId); // current session is the newest, already indexed
        when(sessionRegistry.getAllSessions(USER, false)).thenReturn(List.of(newest, oldest, middle));

        manager.registerAndEnforceLimit(request, USER);

        // 3 active, limit 2 → exactly the single oldest is hard-deleted (propagates logout); others survive.
        verify(sessionRepository).deleteById("s1");
        verify(sessionRepository, times(1)).deleteById(anyString());
    }

    @Test
    void singleRequestCompletionCountsTheCurrentSessionTowardTheCap() {
        // A login that completes in the request that created the session: Spring Session hasn't flushed it
        // to the principal index yet, so getAllSessions omits it. It must still count toward the cap.
        MockHttpServletRequest request = requestWithSession();
        policyWithLimit(1);
        SessionInformation other = mock(SessionInformation.class); // sole element → sort never reads lastRequest
        when(other.getSessionId()).thenReturn("other-id");
        when(sessionRegistry.getAllSessions(USER, false)).thenReturn(List.of(other));

        manager.registerAndEnforceLimit(request, USER);

        // current (uncounted by the registry) + other = 2 > cap 1 → evict the older 'other'.
        verify(sessionRepository).deleteById("other-id");
    }

    @Test
    void terminateForUserDeletesOnlyTheTargetOrgsSessionsAndReturnsTheCount() {
        // Hard-delete (not expireNow mark) fires SessionDeletedEvent -> BCL/SLO. Scoped to the target org so a
        // same-named user in ANOTHER tenant (org B) is never logged out.
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        when(sessionRepository.findByPrincipalName(USER))
                .thenReturn(Map.of("sid-a", sessionBoundTo(orgA), "sid-b", sessionBoundTo(orgB)));

        int count = manager.terminateForUser(USER, orgA);

        verify(sessionRepository).deleteById("sid-a");
        verify(sessionRepository, never()).deleteById("sid-b");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void terminateForUserForANonNullOrgLeavesAMarkerlessGlobalSessionAlone() {
        // The inverse of the null-org case: a TENANT access-change (non-null org) must never delete a user's
        // markerless global/platform session — only the org-marked one goes.
        UUID org = UUID.randomUUID();
        when(sessionRepository.findByPrincipalName(USER))
                .thenReturn(Map.of("sid-global", new MapSession(), "sid-org", sessionBoundTo(org)));

        int count = manager.terminateForUser(USER, org);

        verify(sessionRepository).deleteById("sid-org");
        verify(sessionRepository, never()).deleteById("sid-global"); // markerless global session survives
        assertThat(count).isEqualTo(1);
    }

    @Test
    void terminateForUserWithNoOrgDeletesOnlyTheMarkerlessGlobalSessions() {
        // A global/platform account (orgId null) matches only sessions carrying NO org marker — never a tenant
        // user's org-bound session that happens to share the username.
        UUID orgA = UUID.randomUUID();
        when(sessionRepository.findByPrincipalName(USER))
                .thenReturn(Map.of("sid-global", new MapSession(), "sid-org", sessionBoundTo(orgA)));

        int count = manager.terminateForUser(USER, null);

        verify(sessionRepository).deleteById("sid-global");
        verify(sessionRepository, never()).deleteById("sid-org");
        assertThat(count).isEqualTo(1);
    }

    /** A session whose stored SecurityContext carries the {@code ORG_<orgId>} authority (the login-org marker). */
    private Session sessionBoundTo(UUID orgId) {
        MapSession session = new MapSession();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(USER, null,
                List.of(new SimpleGrantedAuthority(Factors.ORG_PREFIX + orgId)));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(auth));
        return session;
    }

    /** A session carrying both the org marker and a {@code SID_<sid>} marker (as minted at login completion). */
    private Session sessionWith(UUID orgId, String sid) {
        MapSession session = new MapSession();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(USER, null,
                List.of(new SimpleGrantedAuthority(Factors.ORG_PREFIX + orgId),
                        new SimpleGrantedAuthority(Factors.SID_PREFIX + sid)));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(auth));
        return session;
    }

    @Test
    void activeSidsForUserReturnsOnlyTheTargetOrgsSids() {
        // The apps of a same-named user in ANOTHER tenant (org B) must never surface in this user's portal.
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        when(sessionRepository.findByPrincipalName(USER)).thenReturn(Map.of(
                "s-a", sessionWith(orgA, "sid-a"),
                "s-b", sessionWith(orgB, "sid-b")));

        assertThat(manager.activeSidsForUser(USER, orgA)).containsExactly("sid-a");
    }

    @Test
    void activeSidsForUserWithNoOrgReturnsOnlyMarkerlessGlobalSessionSids() {
        UUID org = UUID.randomUUID();
        MapSession global = new MapSession();
        global.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(USER, null,
                        List.of(new SimpleGrantedAuthority(Factors.SID_PREFIX + "sid-global")))));
        when(sessionRepository.findByPrincipalName(USER)).thenReturn(Map.of(
                "s-global", global,
                "s-org", sessionWith(org, "sid-org")));

        assertThat(manager.activeSidsForUser(USER, null)).containsExactly("sid-global");
    }

    @Test
    void activeSidsForUserSkipsSessionsWithoutASidMarker() {
        // A session that predates the SID_ marker (or an unauthenticated one) contributes no sid, silently.
        UUID org = UUID.randomUUID();
        when(sessionRepository.findByPrincipalName(USER)).thenReturn(Map.of(
                "s-nosid", sessionBoundTo(org),          // org marker but no SID_
                "s-sid", sessionWith(org, "sid-1")));

        assertThat(manager.activeSidsForUser(USER, org)).containsExactly("sid-1");
    }

    @Test
    void revokeOfAnUnknownHandleThrowsNotFound() {
        MockHttpServletRequest request = requestWithSession();
        when(sessionMetadata.findByUserAndHandle(USER, "ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.revoke(USER, "ghost")).isInstanceOf(NotFoundException.class);
        verify(sessionMetadata, never()).remove(anyString());
    }

    @Test
    void revokeDeletesTheRegistrySessionAndForgetsItsMetadata() {
        SessionMetadata target = new SessionMetadata("h1", "sid-1", USER, "UA", "ip", Instant.now());
        when(sessionMetadata.findByUserAndHandle(USER, "h1")).thenReturn(Optional.of(target));
        SessionInformation info = mock(SessionInformation.class);
        when(info.getSessionId()).thenReturn("sid-1");
        when(sessionRegistry.getSessionInformation("sid-1")).thenReturn(info);

        manager.revoke(USER, "h1");

        verify(sessionRepository).deleteById("sid-1"); // hard delete -> downstream BCL/SLO, not a mark
        verify(sessionMetadata).remove("sid-1");
    }
}

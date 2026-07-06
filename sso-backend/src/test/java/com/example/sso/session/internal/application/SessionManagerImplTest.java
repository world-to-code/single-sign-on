package com.example.sso.session.internal.application;

import com.example.sso.authpolicy.Factors;
import com.example.sso.organization.OrganizationMembershipChangedEvent;
import com.example.sso.session.SessionMetadata;
import com.example.sso.session.SessionMetadataStore;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.UserAccessChangedEvent;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
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
 * evicts the OLDEST sessions via {@code expireNow()}, at/under-limit evicts nothing, and 0 means
 * unlimited. {@code revoke} is covered for its not-found path.
 */
@ExtendWith(MockitoExtension.class)
class SessionManagerImplTest {

    private static final String USER = "alice";

    @Mock
    private SessionRegistry sessionRegistry;
    @Mock
    private SessionMetadataStore sessionMetadata;
    @Mock
    private SessionPolicyService sessionPolicy;
    @Mock
    private FindByIndexNameSessionRepository<Session> sessionRepository;
    @Mock
    private UserService users;

    private SessionManagerImpl manager;

    @BeforeEach
    void setUp() {
        manager = new SessionManagerImpl(sessionRegistry, sessionMetadata, sessionPolicy, sessionRepository, users);
    }

    private MockHttpServletRequest requestWithSession() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.setSession(new MockHttpSession());
        request.setRemoteAddr("10.0.0.1");
        return request;
    }

    private void policyWithLimit(int max) {
        SessionPolicyDetails policy = mock(SessionPolicyDetails.class);
        when(policy.getMaxConcurrentSessions()).thenReturn(max);
        when(sessionPolicy.resolveForUsername(USER)).thenReturn(policy);
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
    void terminateAllDeletesEveryLiveSessionAndReturnsTheCount() {
        // Hard-delete (not expireNow mark), so the Redis deletion fires SessionDeletedEvent -> BCL/SLO now.
        SessionInformation a = mock(SessionInformation.class);
        SessionInformation b = mock(SessionInformation.class);
        when(a.getSessionId()).thenReturn("sid-a");
        when(b.getSessionId()).thenReturn("sid-b");
        when(sessionRegistry.getAllSessions(USER, false)).thenReturn(List.of(a, b));

        int count = manager.terminateAll(USER);

        verify(sessionRepository).deleteById("sid-a");
        verify(sessionRepository).deleteById("sid-b");
        verify(a, never()).expireNow();
        assertThat(count).isEqualTo(2);
    }

    @Test
    void userAccessChangedEventDeletesTheUsersSessions() {
        SessionInformation a = mock(SessionInformation.class);
        when(a.getSessionId()).thenReturn("sid-a");
        when(sessionRegistry.getAllSessions(USER, false)).thenReturn(List.of(a));

        manager.onUserAccessChanged(new UserAccessChangedEvent(USER));

        verify(sessionRepository).deleteById("sid-a");
    }

    @Test
    void membershipRevokeDeletesOnlyThatOrgsSessions() {
        // The user is a member of two orgs and has a live session in each. Revoking membership in org A must
        // kill only the org-A session; the org-B session (still a valid membership) survives.
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserAccount account = mock(UserAccount.class);
        when(account.getUsername()).thenReturn(USER);
        when(users.findById(userId)).thenReturn(Optional.of(account));
        when(sessionRepository.findByPrincipalName(USER))
                .thenReturn(Map.of("sid-a", sessionBoundTo(orgA), "sid-b", sessionBoundTo(orgB)));

        manager.onOrganizationMembershipChanged(new OrganizationMembershipChangedEvent(orgA, userId));

        verify(sessionRepository).deleteById("sid-a");
        verify(sessionRepository, never()).deleteById("sid-b");
    }

    @Test
    void membershipRevokeForAnUnresolvableUserIsANoOp() {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.empty());

        manager.onOrganizationMembershipChanged(new OrganizationMembershipChangedEvent(UUID.randomUUID(), userId));

        verify(sessionRepository, never()).deleteById(anyString());
        verify(sessionRepository, never()).findByPrincipalName(anyString());
    }

    @Test
    void aSessionWithoutTheOrgMarkerIsNeverTerminated() {
        // A session whose SecurityContext carries no ORG_ marker (or none at all) must be left alone.
        UUID orgA = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserAccount account = mock(UserAccount.class);
        when(account.getUsername()).thenReturn(USER);
        when(users.findById(userId)).thenReturn(Optional.of(account));
        when(sessionRepository.findByPrincipalName(USER))
                .thenReturn(Map.of("sid-none", new MapSession())); // no security context attribute

        manager.onOrganizationMembershipChanged(new OrganizationMembershipChangedEvent(orgA, userId));

        verify(sessionRepository, never()).deleteById(anyString());
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

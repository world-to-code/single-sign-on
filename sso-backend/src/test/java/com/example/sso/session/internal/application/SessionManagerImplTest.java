package com.example.sso.session.internal.application;

import com.example.sso.session.SessionMetadata;
import com.example.sso.session.SessionMetadataStore;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.shared.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private SessionManagerImpl manager;

    @BeforeEach
    void setUp() {
        manager = new SessionManagerImpl(sessionRegistry, sessionMetadata, sessionPolicy);
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
    void firstSightRegistersTheNewSessionAndRecordsMetadata() {
        MockHttpServletRequest request = requestWithSession();
        String id = request.getSession(false).getId();
        when(sessionRegistry.getSessionInformation(id)).thenReturn(null);
        policyWithLimit(0); // unlimited → no eviction path

        manager.registerAndEnforceLimit(request, USER);

        verify(sessionRegistry).registerNewSession(id, USER);
        verify(sessionMetadata).record(eq(id), eq(USER), any(), eq("10.0.0.1"));
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
        policyWithLimit(2);
        SessionInformation a = mock(SessionInformation.class);
        SessionInformation b = mock(SessionInformation.class);
        when(sessionRegistry.getAllSessions(USER, false)).thenReturn(List.of(a, b));

        manager.registerAndEnforceLimit(request, USER);

        verify(a, never()).expireNow();
        verify(b, never()).expireNow();
    }

    @Test
    void overTheLimitEvictsTheOldestSessionsOnly() {
        MockHttpServletRequest request = requestWithSession();
        policyWithLimit(2);
        Instant now = Instant.now();
        SessionInformation oldest = session("s1", now.minusSeconds(300));
        SessionInformation middle = session("s2", now.minusSeconds(200));
        SessionInformation newest = session("s3", now.minusSeconds(100));
        when(sessionRegistry.getAllSessions(USER, false)).thenReturn(List.of(newest, oldest, middle));

        manager.registerAndEnforceLimit(request, USER);

        // 3 active, limit 2 → exactly the single oldest is expired; the other two survive.
        verify(oldest).expireNow();
        verify(middle, never()).expireNow();
        verify(newest, never()).expireNow();
    }

    @Test
    void revokeOfAnUnknownHandleThrowsNotFound() {
        MockHttpServletRequest request = requestWithSession();
        when(sessionMetadata.findByUserAndHandle(USER, "ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.revoke(USER, "ghost")).isInstanceOf(NotFoundException.class);
        verify(sessionMetadata, never()).remove(anyString());
    }

    @Test
    void revokeExpiresTheRegistrySessionAndForgetsItsMetadata() {
        SessionMetadata target = new SessionMetadata("h1", "sid-1", USER, "UA", "ip", Instant.now());
        when(sessionMetadata.findByUserAndHandle(USER, "h1")).thenReturn(Optional.of(target));
        SessionInformation info = mock(SessionInformation.class);
        when(sessionRegistry.getSessionInformation("sid-1")).thenReturn(info);

        manager.revoke(USER, "h1");

        verify(info).expireNow();
        verify(sessionMetadata).remove("sid-1");
    }
}

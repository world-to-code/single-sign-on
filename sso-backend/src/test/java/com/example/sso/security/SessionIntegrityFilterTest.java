package com.example.sso.security;

import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.session.lifecycle.SessionMetadataStore;
import com.example.sso.session.policy.SessionLifetimeFloor;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.UserSessionPolicy;
import com.example.sso.session.lifecycle.StepUpInterceptor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link SessionIntegrityFilter}: the Zero-Trust re-verification every authenticated request
 * runs against the resolved {@link SessionPolicy}. It proves the three independent expiry controls the user
 * defined — absolute lifetime (hard cap from creation), idle timeout (reset by activity), and client (UA)
 * binding — each invalidate + 401 on violation, that a valid session passes while refreshing its activity
 * stamp and driving the container's idle timeout from the policy, and that unauthenticated / session-less
 * requests are skipped. The {@link HttpSession} is a mock so we can control {@code getCreationTime()}.
 */
@ExtendWith(MockitoExtension.class)
class SessionIntegrityFilterTest {

    // Mirror the filter's private session-attribute keys (it does not expose them).
    private static final String LAST_ACTIVITY = "ZT_LAST_ACTIVITY";
    private static final String CLIENT_BINDING = "ZT_CLIENT_BINDING";
    private static final long MIN = 60_000L;

    @Mock private AuditService audit;
    @Mock private UserSessionPolicy policyService;
    @Mock private SessionRegistry sessionRegistry;
    @Mock private SessionMetadataStore sessionMetadata;
    @Mock private SessionPolicyDetails policy;

    private SessionIntegrityFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SessionIntegrityFilter(audit, policyService, sessionRegistry, sessionMetadata);
        lenient().when(policyService.resolveForUsername("alice")).thenReturn(policy);
        // Idle/absolute lifetimes come from the floor (composed across all governing policies), not the winner.
        lenient().when(policyService.lifetimeFloorFor("alice")).thenReturn(new SessionLifetimeFloor(30, 480)); // 30m idle, 8h abs
        lenient().when(policy.getReauthIntervalMinutes()).thenReturn(15);
        lenient().when(policy.getReauthFactors()).thenReturn("TOTP,PASSWORD");
        lenient().when(policy.isBindClient()).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
    }

    private HttpSession session(long createdAgoMillis, Long lastActivityAgoMillis, String boundUa) {
        long now = System.currentTimeMillis();
        HttpSession s = mock(HttpSession.class);
        lenient().when(s.getId()).thenReturn("sid");
        lenient().when(s.getCreationTime()).thenReturn(now - createdAgoMillis);
        lenient().when(s.getAttribute(LAST_ACTIVITY))
                .thenReturn(lastActivityAgoMillis == null ? null : now - lastActivityAgoMillis);
        lenient().when(s.getAttribute(CLIENT_BINDING)).thenReturn(boundUa);
        return s;
    }

    private MockHttpServletRequest request(HttpSession session, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        if (session != null) {
            request.setSession(session);
        }
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        return request;
    }

    @Test
    void aValidSessionPassesRefreshesActivityAndDrivesTheContainerIdleTimeout() throws Exception {
        authenticate();
        HttpSession s = session(MIN, MIN, null); // 1m old, active 1m ago
        MockHttpServletRequest request = request(s, "Mozilla");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(s).setMaxInactiveInterval((int) (30 * 60L + 60)); // idle minutes -> container timeout + grace
        verify(s).setAttribute(eq(LAST_ACTIVITY), any(Long.class));
        verify(sessionMetadata).touch("sid");
        verify(s, never()).invalidate();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aSessionPastItsAbsoluteLifetimeIsInvalidatedAndRejected() throws Exception {
        authenticate();
        HttpSession s = session(500 * MIN, MIN, null); // created 500m ago, but active recently
        MockHttpServletRequest request = request(s, "Mozilla");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(audit).record(AuditType.SESSION_EXPIRED_ABSOLUTE, "alice", false);
        verify(s).invalidate();
        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void aSessionIdleLongerThanTheTimeoutIsInvalidatedAndRejected() throws Exception {
        authenticate();
        HttpSession s = session(MIN, 40 * MIN, null); // young session, but idle 40m > 30m
        MockHttpServletRequest request = request(s, "Mozilla");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(audit).record(AuditType.SESSION_EXPIRED_IDLE, "alice", false);
        verify(s).invalidate();
        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void aChangedClientBindingIsRejectedWhenBindingIsEnabled() throws Exception {
        authenticate();
        when(policy.isBindClient()).thenReturn(true);
        HttpSession s = session(MIN, MIN, "Mozilla/OLD"); // bound to a different UA
        MockHttpServletRequest request = request(s, "Mozilla/NEW");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(audit).record(AuditType.SESSION_CONTEXT_MISMATCH, "alice", false);
        verify(s).invalidate();
        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void aSessionMarkedExpiredByConcurrentControlIsRejected() throws Exception {
        authenticate();
        SessionInformation info = mock(SessionInformation.class);
        when(info.isExpired()).thenReturn(true);
        when(sessionRegistry.getSessionInformation("sid")).thenReturn(info);
        HttpSession s = session(MIN, MIN, null);
        MockHttpServletRequest request = request(s, "Mozilla");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(audit).record(AuditType.SESSION_CONCURRENT_EXPIRED, "alice", false);
        verify(s).invalidate();
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aSessionIdleBeyondTheReauthIntervalIsChallengedForReauthWithoutInvalidating() throws Exception {
        authenticate();
        HttpSession s = session(MIN, MIN, null); // young session, recently active (so not idle/absolute expired)
        when(s.getAttribute(StepUpInterceptor.REAUTH_ACTIVITY)).thenReturn(System.currentTimeMillis() - 20 * MIN);
        MockHttpServletRequest request = request(s, "Mozilla"); // "/api/me" — a protected, non-exempt path
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getHeader("X-Step-Up-Required")).isEqualTo("true");
        assertThat(response.getContentAsString()).contains("\"mandatory\":true").contains("\"TOTP\"");
        verify(s, never()).invalidate(); // the session stays alive — it only needs a fresh re-auth
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aReauthExemptPathIsNotChallengedEvenWhenReauthIsOverdue() throws Exception {
        authenticate();
        HttpSession s = session(MIN, MIN, null);
        // Overdue re-auth, but the exempt path skips the check entirely — so this stub is never read.
        lenient().when(s.getAttribute(StepUpInterceptor.REAUTH_ACTIVITY)).thenReturn(System.currentTimeMillis() - 20 * MIN);
        MockHttpServletRequest request = request(s, "Mozilla");
        request.setRequestURI("/api/auth/session"); // bootstrap/re-auth flow must stay reachable
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void theEmailReVerificationPathStaysReachableWhileReauthIsOverdue() throws Exception {
        // The mandatory re-auth may demand the EMAIL factor, which EmailFactorHandler refuses while the
        // address is unverified (an admin just changed it). If this recovery path were also challenged, the
        // live session would be soft-bricked: the only way to satisfy the challenge is to re-prove the
        // address, and the only way to re-prove it is this endpoint.
        authenticate();
        HttpSession s = session(MIN, MIN, null);
        lenient().when(s.getAttribute(StepUpInterceptor.REAUTH_ACTIVITY))
                .thenReturn(System.currentTimeMillis() - 20 * MIN);
        FilterChain chain = mock(FilterChain.class);

        for (String path : new String[] {"/api/auth/email-verification", "/api/auth/email-verification/confirm"}) {
            MockHttpServletRequest request = request(s, "Mozilla");
            request.setRequestURI(path);
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }

        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    void anActiveSessionWithinTheReauthIntervalRefreshesTheReauthClock() throws Exception {
        authenticate();
        HttpSession s = session(MIN, MIN, null);
        when(s.getAttribute(StepUpInterceptor.REAUTH_ACTIVITY)).thenReturn(System.currentTimeMillis() - 2 * MIN);
        MockHttpServletRequest request = request(s, "Mozilla");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        verify(s).setAttribute(eq(StepUpInterceptor.REAUTH_ACTIVITY), any(Long.class)); // activity keeps it fresh
    }

    @Test
    void anUnauthenticatedRequestSkipsAllPolicyChecks() throws Exception {
        // no authentication in the context
        HttpSession s = session(MIN, MIN, null);
        MockHttpServletRequest request = request(s, "Mozilla");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        verifyNoInteractions(policyService, audit);
        verify(s, never()).invalidate();
        verify(s, never()).setMaxInactiveInterval(anyInt());
    }

    @Test
    void aRequestWithoutASessionIsPassedThrough() throws Exception {
        authenticate();
        MockHttpServletRequest request = request(null, "Mozilla"); // no session
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        verifyNoInteractions(policyService, audit);
    }
}

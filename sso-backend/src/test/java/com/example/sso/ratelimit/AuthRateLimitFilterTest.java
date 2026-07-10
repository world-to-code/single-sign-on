package com.example.sso.ratelimit;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which requests the rate limiter actually guards, and what a refusal looks like. The bucket algebra is the
 * library's ({@link RateLimiter} / Bucket4j); what this pins is the POLICY the filter encodes: the limited
 * path set, POST-only, one bucket per (path, client IP), a 429 that never reaches the chain, and the audit
 * trail a refusal leaves. Dropping a path from the set — the exact regression this change could cause —
 * fails here.
 */
@ExtendWith(MockitoExtension.class)
class AuthRateLimitFilterTest {

    private static final String IP = "203.0.113.7";

    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private AuditService audit;
    @Mock
    private FilterChain chain;

    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthRateLimitFilter(rateLimiter, audit);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr(IP);
        return request;
    }

    private MockHttpServletResponse pass(String method, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(method, path), response, chain);
        return response;
    }

    @Test
    void everyAuthEndpointThatBurnsASecretIsLimited() throws Exception {
        when(rateLimiter.tryAcquire(any())).thenReturn(true);

        for (String path : new String[] {
                "/api/auth/identify", "/api/auth/login",
                // A code is MAILED on these: unlimited requests are a mail-bomb and a code-grinding oracle.
                "/api/auth/email-verification", "/api/auth/email-verification/confirm",
                "/api/auth/factors/TOTP/verify", "/api/auth/reauth/PASSWORD/verify",
                "/api/onboarding/apply", "/api/onboarding/activate", "/api/onboarding/set-password" }) {
            filter.doFilter(request("POST", path), new MockHttpServletResponse(), chain);
            verify(rateLimiter).tryAcquire(path + ":" + IP); // one bucket per (path, client ip)
        }
    }

    @Test
    void anUnlistedPathAndANonPostAreNotLimited() throws Exception {
        pass("POST", "/api/auth/session");
        pass("GET", "/api/auth/login"); // reading the login page never spends a token

        verify(rateLimiter, never()).tryAcquire(any());
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    void anAllowedRequestReachesTheChainAndIsNotAudited() throws Exception {
        when(rateLimiter.tryAcquire(any())).thenReturn(true);

        MockHttpServletResponse response = pass("POST", "/api/auth/login");

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain).doFilter(any(), any());
        verify(audit, never()).record(any());
    }

    @Test
    void anExhaustedBucketRefusesWithTooManyRequestsAndNeverReachesTheChain() throws Exception {
        when(rateLimiter.tryAcquire(any())).thenReturn(false);

        MockHttpServletResponse response = pass("POST", "/api/auth/login");

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        verify(chain, never()).doFilter(any(), any()); // the credential is never even examined
    }

    @Test
    void aRefusalIsAuditedWithTheClientAddress() throws Exception {
        when(rateLimiter.tryAcquire(any())).thenReturn(false);

        pass("POST", "/api/auth/email-verification");

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(record.capture());
        assertThat(record.getValue().type()).isEqualTo(AuditType.RATE_LIMITED);
        assertThat(record.getValue().success()).isFalse();
        assertThat(record.getValue().remoteIp()).isEqualTo(IP);
        assertThat(record.getValue().detail()).isEqualTo("/api/auth/email-verification");
    }
}

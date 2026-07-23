package com.example.sso.ratelimit;

import com.example.sso.ratelimit.internal.RateLimiter;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.factor.Factors;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

    /**
     * A CSV-import request from an admin whose SESSION is bound to the given org (its {@code ORG_} marker) — the
     * tenant a real login carries, not anything the request host claims. A null org models a super (no marker).
     */
    private void csvImport(UUID org, String principal, String path) throws Exception {
        csvImportFromHost(org, principal, path, null);
    }

    /** As above, but with an explicit request host, to prove the host does NOT decide the bucket. */
    private void csvImportFromHost(UUID org, String principal, String path, String host) throws Exception {
        List<SimpleGrantedAuthority> authorities = org == null ? List.of()
                : List.of(new SimpleGrantedAuthority(Factors.ORG_PREFIX + org));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
        try {
            MockHttpServletRequest request = request("POST", path);
            if (host != null) {
                request.setServerName(host);
            }
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** The session's ORG_ marker, verbatim — the whole marker is the tenant discriminator in the bucket key. */
    private String orgKey(UUID org) {
        return Factors.ORG_PREFIX + org;
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

    /**
     * Federation start/callback are browser-navigation GETs, so a method-gated limiter never looked at them —
     * yet each one drives an unauthenticated outbound fetch to the upstream (thread exhaustion) and the
     * callback creates accounts and sessions. Throttle by what the endpoint DOES, not by its verb.
     */
    @Test
    void throttlesTheFederationStartEvenThoughItIsAGet() throws Exception {
        when(rateLimiter.tryAcquire(any())).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/federation/okta/start");
        request.setServletPath("/api/auth/federation/okta/start");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void throttlesTheFederationCallbackEvenThoughItIsAGet() throws Exception {
        when(rateLimiter.tryAcquire(any())).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/federation/okta/callback");
        request.setServletPath("/api/auth/federation/okta/callback");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void leavesAnUnrelatedGetAlone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/session");
        request.setServletPath("/api/auth/session");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(rateLimiter, never()).tryAcquire(any());
    }

    private static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GLOBEX = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /**
     * One budget per administrator for the whole import feature. The route carries the profile id and the
     * preview-vs-apply suffix, so keying on the full path gave a separate budget per profile and per phase —
     * a person with two profiles, or one who previews then applies, got several where the intent was one. The
     * bucket segment is fixed; the discriminator is (tenant, principal) — here one tenant, one admin, one bucket.
     */
    @Test
    void oneCsvImportBudgetPerAdministratorAcrossProfilesAndPhases() throws Exception {
        when(rateLimiter.tryAcquire(any())).thenReturn(true);

        csvImport(ACME, "ada", "/api/admin/profiles/p-1/csv-import/preview");
        csvImport(ACME, "ada", "/api/admin/profiles/p-2/csv-import");

        verify(rateLimiter, times(2)).tryAcquire("/csv-import:" + orgKey(ACME) + ":ada"); // same bucket both times
    }

    /**
     * The regression this fix exists for. The principal is the per-org username (tenant = organization), so it
     * is unique only WITHIN a tenant: two tenants can each have an admin named "ada". Keying on the principal
     * alone put them in one bucket, letting a busy admin in one tenant starve the other. The tenant, from the
     * session's ORG_ marker, must keep them apart.
     */
    @Test
    void sameNamedAdminsInDifferentTenantsDoNotShareABudget() throws Exception {
        when(rateLimiter.tryAcquire(any())).thenReturn(true);

        csvImport(ACME, "ada", "/api/admin/profiles/p-1/csv-import");
        csvImport(GLOBEX, "ada", "/api/admin/profiles/p-9/csv-import");

        verify(rateLimiter).tryAcquire("/csv-import:" + orgKey(ACME) + ":ada");
        verify(rateLimiter).tryAcquire("/csv-import:" + orgKey(GLOBEX) + ":ada"); // a distinct bucket, not the same one
    }

    /**
     * The tenant comes from the SESSION, never the request host. An acme admin who spoofs a globex Host must not
     * be charged to (and so cannot starve) globex's bucket — the key stays keyed to their own ORG_ marker.
     */
    @Test
    void aSpoofedHostDoesNotChargeAnotherTenantsBucket() throws Exception {
        when(rateLimiter.tryAcquire(any())).thenReturn(true);

        csvImportFromHost(ACME, "ada", "/api/admin/profiles/p-1/csv-import", "globex.sso.example");

        verify(rateLimiter).tryAcquire("/csv-import:" + orgKey(ACME) + ":ada"); // acme's session, not globex's host
        verify(rateLimiter, never()).tryAcquire("/csv-import:" + orgKey(GLOBEX) + ":ada");
    }
}

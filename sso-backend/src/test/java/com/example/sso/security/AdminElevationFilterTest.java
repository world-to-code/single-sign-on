package com.example.sso.security;

import com.example.sso.audit.AuditService;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.portal.binding.AdminConsoleConfigService;
import com.example.sso.portal.binding.AdminConsoleConfigView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AdminElevationFilter}: the per-tenant issuer derivation, and the elevation-freshness rule. The token
 * an admin obtains at their TENANT subdomain carries the host-derived issuer ({@code http://acme.localhost:9000}),
 * so the gate must expect THAT issuer — not a fixed platform issuer — or every tenant admin is rejected (401) at
 * their own subdomain. The elevation lasts the console's ELEVATION TTL (the {@code elevationWindow}); it is NOT
 * the stricter sensitive-action window — a short step-up window must no longer force the whole console to
 * re-elevate on every request.
 */
class AdminElevationFilterTest {

    private static final String PLATFORM_ISSUER = "http://localhost:9000";
    private static final String SUBJECT = "admin@example.com";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private AdminElevationFilter filter() {
        // isElevated only reads the token and the clientId; the other collaborators are never touched here.
        return new AdminElevationFilter(null, PLATFORM_ISSUER, AdminPortalSeeder.CLIENT_ID, null, null);
    }

    /** An admin-console elevation token issued / stepped-up the given number of seconds ago. */
    private Jwt elevationToken(long issuedSecondsAgo, Long stepUpSecondsAgo) {
        long now = Instant.now().getEpochSecond();
        Jwt.Builder token = Jwt.withTokenValue("t").header("alg", "none")
                .subject(SUBJECT)
                .issuer(PLATFORM_ISSUER)
                .claim("azp", AdminPortalSeeder.CLIENT_ID)
                .claim("scope", List.of(AdminPortalSeeder.ADMIN_SCOPE))
                .claim("acr", "mfa")
                .issuedAt(Instant.ofEpochSecond(now - issuedSecondsAgo));
        if (stepUpSecondsAgo != null) {
            token.claim("stepup_time", String.valueOf(now - stepUpSecondsAgo));
        }
        return token.build();
    }

    /**
     * Drives the WHOLE filter (not just {@code isElevated}) with a decoded token + a stubbed console TTL, so the
     * test pins that {@code doFilterInternal} feeds the CONSOLE's elevation TTL into the freshness check — the
     * exact wiring the bug lived in. Returns the response status (200 = accepted through the chain).
     */
    private int filterStatus(Jwt decoded, int configuredTtlMinutes) throws Exception {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("tok")).thenReturn(decoded);
        AdminConsoleConfigService config = mock(AdminConsoleConfigService.class);
        when(config.current()).thenReturn(new AdminConsoleConfigView(configuredTtlMinutes, null));
        AdminElevationFilter filter = new AdminElevationFilter(
                decoder, PLATFORM_ISSUER, AdminPortalSeeder.CLIENT_ID, config, mock(AuditService.class));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SUBJECT, null, List.of())); // session principal == sub

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.setServletPath("/api/admin/users");
        request.setScheme("http");
        request.addHeader("Host", "localhost:9000");   // expectedIssuer == PLATFORM_ISSUER
        request.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void theFilterAcceptsAStepUpWithinTheConfiguredConsoleElevationTtl() throws Exception {
        // WIRING pin: 3-minute-old step-up, tenant console TTL 30 min → accepted (200). If doFilterInternal fed
        // the old 2-minute sensitive-action window (the bug) instead of config.elevationTokenTtlMinutes(), this
        // request would 401 — so this is the test that would have caught the original defect.
        assertThat(filterStatus(elevationToken(180, 180L), 30)).isEqualTo(200);
    }

    @Test
    void theFilterRejectsAStepUpOlderThanTheConfiguredConsoleElevationTtl() throws Exception {
        // Same 3-minute-old token, but the tenant TTL is only 2 min → 401. Confirms the filter reads the window
        // from the console config (not a hardcoded constant): the config value is what flips accept↔reject.
        assertThat(filterStatus(elevationToken(180, 180L), 2)).isEqualTo(401);
    }

    @Test
    void elevatedWhileTheStepUpIsWithinTheElevationWindow() {
        // 3 minutes since the deliberate step-up, a 30-minute elevation TTL → still elevated. Under the old
        // rule (which used the 2-minute sensitive-action window) this exact token was rejected — the fix.
        assertThat(filter().isElevated(elevationToken(180, 180L), Duration.ofMinutes(30), PLATFORM_ISSUER)).isTrue();
    }

    @Test
    void notElevatedOnceTheStepUpIsOlderThanTheElevationWindow() {
        assertThat(filter().isElevated(elevationToken(60, 1860L), Duration.ofMinutes(30), PLATFORM_ISSUER)).isFalse();
    }

    @Test
    void notElevatedOnceTheTokenItselfIsOlderThanTheElevationWindow() {
        assertThat(filter().isElevated(elevationToken(1860, 60L), Duration.ofMinutes(30), PLATFORM_ISSUER)).isFalse();
    }

    @Test
    void notElevatedWithoutADeliberateStepUp() {
        // A plain login (no stepup_time) never elevates, regardless of how fresh the token is.
        assertThat(filter().isElevated(elevationToken(10, null), Duration.ofMinutes(30), PLATFORM_ISSUER)).isFalse();
    }

    @Test
    void theWindowBoundIsInclusive() {
        // Exactly at the window edge is still elevated; one second past it is not — for BOTH age axes (guards the
        // <= boundary against an off-by-one flip to <).
        assertThat(filter().isElevated(elevationToken(60, 1800L), Duration.ofMinutes(30), PLATFORM_ISSUER)).isTrue();
        assertThat(filter().isElevated(elevationToken(60, 1801L), Duration.ofMinutes(30), PLATFORM_ISSUER)).isFalse();
        assertThat(filter().isElevated(elevationToken(1800, 60L), Duration.ofMinutes(30), PLATFORM_ISSUER)).isTrue();
        assertThat(filter().isElevated(elevationToken(1801, 60L), Duration.ofMinutes(30), PLATFORM_ISSUER)).isFalse();
    }

    /** A fresh, fully-valid elevation token (issued + stepped-up 10s ago) — each claim-gate test spoils ONE field. */
    private Jwt.Builder freshToken() {
        long now = Instant.now().getEpochSecond();
        return Jwt.withTokenValue("t").header("alg", "none").subject(SUBJECT).issuer(PLATFORM_ISSUER)
                .claim("azp", AdminPortalSeeder.CLIENT_ID).claim("scope", List.of(AdminPortalSeeder.ADMIN_SCOPE))
                .claim("acr", "mfa").issuedAt(Instant.ofEpochSecond(now - 10))
                .claim("stepup_time", String.valueOf(now - 10));
    }

    @Test
    void notElevatedWhenTheTokenIsForAnotherClient() {
        Jwt jwt = freshToken().claim("azp", "some-other-client").build();
        assertThat(filter().isElevated(jwt, Duration.ofMinutes(30), PLATFORM_ISSUER)).isFalse();
    }

    @Test
    void notElevatedWithoutTheAdminScope() {
        long now = Instant.now().getEpochSecond();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(SUBJECT).issuer(PLATFORM_ISSUER)
                .claim("azp", AdminPortalSeeder.CLIENT_ID).claim("acr", "mfa") // no scope claim at all
                .issuedAt(Instant.ofEpochSecond(now - 10)).claim("stepup_time", String.valueOf(now - 10)).build();
        assertThat(filter().isElevated(jwt, Duration.ofMinutes(30), PLATFORM_ISSUER)).isFalse();
    }

    @Test
    void notElevatedWhenTheAcrIsNotMfa() {
        // A single-factor acr must be refused — this codebase previously shipped an acr=sfa elevation loop.
        Jwt jwt = freshToken().claim("acr", "sfa").build();
        assertThat(filter().isElevated(jwt, Duration.ofMinutes(30), PLATFORM_ISSUER)).isFalse();
    }

    @Test
    void notElevatedWhenTheIssuerDiffersFromTheExpectedHost() {
        // A token minted under another tenant's issuer must be refused at this host.
        assertThat(filter().isElevated(freshToken().build(), Duration.ofMinutes(30), "http://evil.localhost:9000"))
                .isFalse();
    }

    private MockHttpServletRequest request(String scheme, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme);
        if (host != null) {
            request.addHeader("Host", host);
        }
        return request;
    }

    @Test
    void expectsTheTenantHostIssuerAtATenantSubdomain() {
        assertThat(AdminElevationFilter.expectedIssuer(request("http", "acme.localhost:9000"), PLATFORM_ISSUER))
                .isEqualTo("http://acme.localhost:9000");
    }

    @Test
    void expectsThePlatformIssuerAtTheBareHost() {
        assertThat(AdminElevationFilter.expectedIssuer(request("http", "localhost:9000"), PLATFORM_ISSUER))
                .isEqualTo(PLATFORM_ISSUER);
    }

    @Test
    void preservesSchemeAndPortFromTheRequest() {
        assertThat(AdminElevationFilter.expectedIssuer(request("https", "acme.idp.example.com"), PLATFORM_ISSUER))
                .isEqualTo("https://acme.idp.example.com");
    }

    @Test
    void fallsBackToTheConfiguredIssuerWhenHostIsAbsent() {
        assertThat(AdminElevationFilter.expectedIssuer(request("http", null), PLATFORM_ISSUER))
                .isEqualTo(PLATFORM_ISSUER);
    }
}

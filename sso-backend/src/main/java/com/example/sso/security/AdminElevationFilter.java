package com.example.sso.security;

import com.example.sso.portal.access.AppAssignmentFilter;

import com.example.sso.audit.AuditType;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.portal.binding.AdminConsoleConfigService;
import com.example.sso.portal.binding.AdminConsoleConfigView;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * RFC 9470 step-up elevation gate for the admin API. The session cookie + {@code @PreAuthorize} PBAC
 * still establish identity and fine-grained permissions (and run first — this filter is anchored
 * AFTER the authorization filter); this is a purely additive proof-of-elevation check.
 *
 * <p>On {@code /api/admin/**} a fresh bearer access token from the {@code admin-console} OIDC flow is
 * REQUIRED. The token must:
 * <ul>
 *   <li>be issued by THIS IdP ({@code iss}) for the {@code admin-console} client ({@code azp}) — so a
 *       token minted for another client (even for the same user) cannot elevate;</li>
 *   <li>carry the {@code admin} scope (issuable only to a user the console app is ASSIGNED to —
 *       {@code AppAssignmentFilter} gates {@code /oauth2/authorize}; no role check here);</li>
 *   <li>assert {@code acr=mfa} (a strong/multi factor); and</li>
 *   <li>carry a {@code stepup_time} (set only on a DELIBERATE {@code /reauth}, NOT on plain login)
 *       within {@link #FRESHNESS_WINDOW} — proving a recent re-authentication, not just a recent login;</li>
 *   <li>have {@code sub} equal to the current session principal (no cross-user / cross-session replay).</li>
 * </ul>
 * Any failure yields a {@code 401} with the RFC 9470 {@code WWW-Authenticate: Bearer
 * error="insufficient_user_authentication", acr_values="mfa"} challenge so the SPA can re-elevate.
 */
@RequiredArgsConstructor
public class AdminElevationFilter extends OncePerRequestFilter {

    static final String REQUIRED_SCOPE = AdminPortalSeeder.ADMIN_SCOPE;
    static final String REQUIRED_ACR = "mfa";

    private static final String INSUFFICIENT = "insufficient_user_authentication";
    private static final String CHALLENGE =
            "Bearer error=\"" + INSUFFICIENT + "\", acr_values=\"" + REQUIRED_ACR + "\"";

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final JwtDecoder jwtDecoder;
    private final String issuer;
    private final String clientId;
    /** The console's per-tenant enforcement config: elevation-token lifetime + entry IP allowlist. */
    private final AdminConsoleConfigService consoleConfig;
    private final AuditService audit;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !(path.equals("/api/admin") || path.startsWith("/api/admin/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            challenge(response);
            return;
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(header.substring(7).trim()); // validates signature + expiry
        } catch (JwtException e) {
            challenge(response);
            return;
        }

        // Elevation TTL + IP allowlist come from the admin console's per-tenant config (own row, else the global
        // default), resolved for the acting tenant — an un-drilled super-admin resolves the global default.
        AdminConsoleConfigView config = consoleConfig.current();
        // Keyed on the direct peer address (getRemoteAddr), NOT the spoofable X-Forwarded-For. Behind a
        // reverse proxy that does not preserve the client IP, allowlist the proxy's address. The allowlist is
        // per-tenant now: an admin (or a super-admin drilled into that tenant) whose network is excluded is
        // locked out of THAT tenant's console only — recover by editing the tenant's row in the DB.
        if (!AdminConsoleNetwork.allows(config.adminAllowedCidrs(), request.getRemoteAddr())) {
            audit.record(new AuditRecord(AuditType.ADMIN_IP_BLOCKED, jwt.getSubject(), false,
                    "uri=" + request.getRequestURI(), request.getRemoteAddr()));
            forbidNetwork(response);
            return;
        }
        // The elevation lasts the console's ELEVATION TTL — how long a fresh step-up keeps the console unlocked.
        // It is NOT the sensitive-action window: that stricter freshness (sensitiveReauthWindowMinutes) governs
        // only individual @RequireStepUp destructive actions (StepUpInterceptor), so a short action window can no
        // longer force the WHOLE console to re-elevate on every request. Idle/absolute session lifetimes are
        // enforced for every authenticated request by SessionIntegrityFilter, independent of this gate.
        Duration elevationWindow = Duration.ofMinutes(config.elevationTokenTtlMinutes());
        if (!isElevated(jwt, elevationWindow, expectedIssuer(request, issuer))
                || !boundToSession(jwt)) {
            // A decoded token that fails elevation or session-binding is the forge/replay/stale signal — audit it.
            audit.record(new AuditRecord(AuditType.ADMIN_ELEVATION_DENIED, jwt.getSubject(), false,
                    "uri=" + request.getRequestURI(), request.getRemoteAddr()));
            challenge(response);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Issued by this IdP for admin-console, scope=admin, acr=mfa, and a fresh step-up. No role check:
     * console entry is assignment-based — this token is only issuable to an ASSIGNED user
     * ({@code AppAssignmentFilter} gates {@code /oauth2/authorize}), and what the caller may do is
     * decided per endpoint by {@code @RequirePermission}.
     */
    boolean isElevated(Jwt jwt, Duration elevationWindow, String expectedIssuer) {
        if (jwt.getIssuer() == null || !expectedIssuer.equals(jwt.getIssuer().toString())) {
            return false;
        }
        if (!clientId.equals(jwt.getClaimAsString("azp"))) {
            return false; // token must be bound to the admin-console client
        }
        if (!scopes(jwt).contains(REQUIRED_SCOPE)) {
            return false;
        }
        if (!REQUIRED_ACR.equals(jwt.getClaimAsString("acr"))) {
            return false;
        }

        // The elevation lasts the console's ELEVATION TTL, enforced PER TENANT here. The shared admin-console
        // client mints a long-lived token (one client serves every tenant); the acting tenant's TTL bounds how
        // long that token actually elevates, measured from its issuance (iat).
        Instant issuedAt = jwt.getIssuedAt();
        if (issuedAt == null) {
            return false;
        }
        long tokenAge = Instant.now().getEpochSecond() - issuedAt.getEpochSecond();
        if (tokenAge < 0 || tokenAge > elevationWindow.toSeconds()) {
            return false;
        }

        // Require a DELIBERATE step-up (not a plain login), fresh within the SAME elevation window — so the
        // token proves a recent re-authentication, and the window is the one knob (elevation TTL) that governs it.
        Long stepUp = epochSeconds(jwt, "stepup_time");
        if (stepUp == null) {
            return false;
        }

        long stepUpAge = Instant.now().getEpochSecond() - stepUp;
        return stepUpAge >= 0 && stepUpAge <= elevationWindow.toSeconds();
    }

    /**
     * The issuer the admin-console token MUST carry: this IdP AT THE REQUEST'S OWN HOST. The per-tenant issuer
     * is host-derived (a token minted at {@code acme.localhost:9000} carries {@code iss=http://acme.localhost:9000}),
     * so a fixed platform issuer would reject every tenant admin at their subdomain. Deriving it from the request
     * host matches how the token was minted AND pins the token to this host — a token from ANOTHER tenant's issuer
     * is refused (belt-and-suspenders with the host-scoped signature check). Falls back to the configured platform
     * issuer if the Host header is absent.
     */
    static String expectedIssuer(HttpServletRequest request, String fallbackIssuer) {
        String host = request.getHeader("Host");
        return host == null || host.isBlank() ? fallbackIssuer : request.getScheme() + "://" + host;
    }

    /** sub must equal the current session principal so a token for another user cannot be replayed. */
    private boolean boundToSession(Jwt jwt) {
        Authentication authentication = contextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getName().equals(jwt.getSubject());
    }

    private List<String> scopes(Jwt jwt) {
        Object scope = jwt.getClaim("scope");
        if (scope instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (scope instanceof String s) {
            return List.of(s.split(" "));
        }
        return Collections.emptyList();
    }

    /** A time claim (epoch seconds), encoded as a String, a number, or a temporal/Instant. */
    private Long epochSeconds(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof Instant instant) {
            return instant.getEpochSecond();
        }
        return null;
    }

    private void challenge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + INSUFFICIENT + "\"}");
    }

    /** A 403 (not a re-elevation challenge) when the admin console is barred from this network. */
    private void forbidNetwork(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"admin console is not permitted from your network\"}");
    }
}

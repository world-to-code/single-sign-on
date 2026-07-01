package com.example.sso.security;

import com.example.sso.user.Roles;
import com.example.sso.admin.AdminPortalSettingsData;
import com.example.sso.admin.AdminPortalSettingsService;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.oidc.AdminPortalSeeder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
import java.util.Set;

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
 *   <li>carry the {@code admin} scope and {@code roles} containing {@code ROLE_ADMIN};</li>
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
    /** Admin-tier roles that may elevate: the super admin and the scoped (group) admin. */
    static final Set<String> ADMIN_TIER_ROLES = Set.of(Roles.ADMIN, Roles.GROUP_ADMIN);

    private static final String INSUFFICIENT = "insufficient_user_authentication";
    private static final String CHALLENGE =
            "Bearer error=\"" + INSUFFICIENT + "\", acr_values=\"" + REQUIRED_ACR + "\"";

    /** Session attributes tracking the admin-elevation session's start and last-activity (epoch seconds). */
    private static final String ADMIN_FIRST_SEEN = AdminElevationFilter.class.getName() + ".firstSeen";
    private static final String ADMIN_LAST_SEEN = AdminElevationFilter.class.getName() + ".lastSeen";

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final JwtDecoder jwtDecoder;
    private final String issuer;
    private final String clientId;
    /** Runtime-editable admin-portal knobs (freshness window + session idle/absolute lifetimes). */
    private final AdminPortalSettingsService settingsService;
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

        AdminPortalSettingsData settings = settingsService.get();
        // Keyed on the direct peer address (getRemoteAddr), NOT the spoofable X-Forwarded-For. Behind a
        // reverse proxy that does not preserve the client IP, allowlist the proxy's address. An operator
        // who allowlists a range excluding their own network locks the console out (recover via DB).
        if (!settings.ipAllowed(request.getRemoteAddr())) {
            audit.record(new AuditRecord(AuditType.ADMIN_IP_BLOCKED, jwt.getSubject(), false,
                    "uri=" + request.getRequestURI(), request.getRemoteAddr()));
            forbidNetwork(response);
            return;
        }
        if (!isElevated(jwt, settings.reauthInterval()) || !boundToSession(jwt)) {
            challenge(response);
            return;
        }
        if (!withinSessionWindow(request, settings)) {
            challenge(response);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Bounds the admin-elevation session by idle and absolute lifetime, tracked in the HTTP session.
     * When either window is exceeded the admin timestamps are cleared and the request is challenged, so
     * the SPA re-elevates (a fresh step-up) which restarts the windows.
     */
    private boolean withinSessionWindow(HttpServletRequest request, AdminPortalSettingsData settings) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false; // an authenticated admin request always carries a session
        }

        long now = Instant.now().getEpochSecond();
        Long firstSeen = (Long) session.getAttribute(ADMIN_FIRST_SEEN);
        Long lastSeen = (Long) session.getAttribute(ADMIN_LAST_SEEN);
        if (firstSeen != null && now - firstSeen > settings.sessionAbsoluteLifetime().toSeconds()) {
            clearAdminSession(session);
            return false;
        }
        if (lastSeen != null && now - lastSeen > settings.sessionIdleTimeout().toSeconds()) {
            clearAdminSession(session);
            return false;
        }

        if (firstSeen == null) {
            session.setAttribute(ADMIN_FIRST_SEEN, now);
        }
        session.setAttribute(ADMIN_LAST_SEEN, now);

        return true;
    }

    private void clearAdminSession(HttpSession session) {
        session.removeAttribute(ADMIN_FIRST_SEEN);
        session.removeAttribute(ADMIN_LAST_SEEN);
    }

    /** Issued by this IdP for admin-console, scope=admin, ROLE_ADMIN, acr=mfa, and a fresh step-up. */
    private boolean isElevated(Jwt jwt, Duration freshnessWindow) {
        if (jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString())) {
            return false;
        }
        if (!clientId.equals(jwt.getClaimAsString("azp"))) {
            return false; // token must be bound to the admin-console client
        }
        if (!scopes(jwt).contains(REQUIRED_SCOPE)) {
            return false;
        }
        if (claimList(jwt, "roles").stream().noneMatch(ADMIN_TIER_ROLES::contains)) {
            return false; // the token itself must belong to an admin-tier role
        }
        if (!REQUIRED_ACR.equals(jwt.getClaimAsString("acr"))) {
            return false;
        }

        Long stepUp = epochSeconds(jwt, "stepup_time"); // null => no deliberate re-auth => reject
        if (stepUp == null) {
            return false;
        }

        long age = Instant.now().getEpochSecond() - stepUp;
        return age >= 0 && age <= freshnessWindow.toSeconds();
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

    private List<String> claimList(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : Collections.emptyList();
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

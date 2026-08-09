package com.example.sso.response;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the response API's caller: an OAuth 2.0 {@code client_credentials} token this IdP issued,
 * under THIS host's issuer, to a client registered in THIS host's tenant.
 *
 * <p>Each of those three is load-bearing and they fail differently.
 *
 * <p><b>This host's issuer.</b> The issuer is derived from the request host, so a token minted at tenant A's
 * subdomain must not be spendable at tenant B's — that is the whole point of the per-tenant issuer, and the
 * check is the same one the admin-console elevation filter makes.
 *
 * <p><b>This host's tenant.</b> The client is resolved through the org-scoped registered-client repository,
 * which returns nothing for a client owned by another organization. Combined with the issuer check that is
 * belt and braces on the same property, deliberately: a signing key is a longer-lived secret than a client
 * registration, and neither alone should be the only thing between two tenants.
 *
 * <p><b>A machine.</b> The client must actually allow {@code client_credentials}. Without it, an interactive
 * client configured with a response scope would let a USER's token drive the response API — a person acting
 * with a machine's authority, recorded as a machine.
 *
 * <p>A correlation id is mandatory on every call. It is what lets an investigator join a hold here to the
 * detection that caused it over there; a response nobody can trace back to a reason is one nobody can
 * evaluate, and by the time somebody asks, the answer has to already be in the row.
 *
 * <p>Instantiated by its security config, never a {@code @Component} — an auto-registered filter would also
 * run on the main application chain, where a response token would become a session credential.
 */
@RequiredArgsConstructor
public class ResponseApiTokenFilter extends OncePerRequestFilter {

    /** The reserved actor name every response call is recorded as, classified SERVICE by the audit trail. */
    public static final String RESPONSE_PRINCIPAL = "response-client";

    /** Joins an action here to the detection that asked for it, in whatever the caller's own id space is. */
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_CORRELATION_LENGTH = 128;

    private final JwtDecoder jwtDecoder;
    private final RegisteredClientRepository clients;
    private final ResponseCorrelation correlation;
    private final AuditService audit;
    /** The issuer to fall back on when a request carries no Host header (never, in practice, over HTTP/1.1). */
    private final String fallbackIssuer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Jwt jwt = machineToken(request);
        if (jwt == null) {
            unauthorized(request, response);
            return;
        }
        String correlationId = correlationId(request);
        if (correlationId == null) {
            // A 400, not a 401: the credential was fine and the CALL was not. Told apart because a response
            // system retrying a 401 re-authenticates forever against a request that will never be accepted.
            audit.record(new AuditRecord(AuditType.RESPONSE_ACTION_REFUSED, RESPONSE_PRINCIPAL, false,
                    "uri=" + request.getRequestURI(), request.getRemoteAddr()).withReason("correlation.missing"));
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        correlation.bind(correlationId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(RESPONSE_PRINCIPAL, null, scopeAuthorities(jwt)));
        chain.doFilter(request, response);
    }

    /** The decoded token, if it is one this host issued to a machine client of this host's tenant. */
    private Jwt machineToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(header.substring(BEARER_PREFIX.length()).trim()); // signature + expiry
        } catch (JwtException notOurs) {
            return null;
        }
        if (jwt.getIssuer() == null || !expectedIssuer(request).equals(jwt.getIssuer().toString())) {
            return null;
        }
        return isMachineClientOfThisTenant(jwt.getSubject()) ? jwt : null;
    }

    /**
     * For a {@code client_credentials} token the subject IS the client. The repository is org-scoped, so a
     * client belonging to another tenant resolves to nothing here rather than to somebody else's client.
     */
    private boolean isMachineClientOfThisTenant(String clientId) {
        if (clientId == null) {
            return false;
        }
        RegisteredClient client = clients.findByClientId(clientId);
        return client != null
                && client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.CLIENT_CREDENTIALS);
    }

    private String correlationId(HttpServletRequest request) {
        String value = request.getHeader(CORRELATION_HEADER);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.length() > MAX_CORRELATION_LENGTH ? null : trimmed;
    }

    /** The token's scopes as authorities, matching what {@code @PreAuthorize("hasAuthority('SCOPE_…')")} reads. */
    private List<SimpleGrantedAuthority> scopeAuthorities(Jwt jwt) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (String scope : scopes(jwt)) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }
        return authorities;
    }

    private List<String> scopes(Jwt jwt) {
        Object scope = jwt.getClaim("scope");
        if (scope instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return scope instanceof String single ? List.of(single.split(" ")) : List.of();
    }

    /** The issuer this host mints under — the per-tenant issuer, read the way the elevation filter reads it. */
    private String expectedIssuer(HttpServletRequest request) {
        String host = request.getHeader(HttpHeaders.HOST);
        return host == null || host.isBlank() ? fallbackIssuer : request.getScheme() + "://" + host;
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Every rejected credential is on the trail: an API this powerful being probed is itself the signal.
        audit.record(new AuditRecord(AuditType.RESPONSE_ACTION_REFUSED, RESPONSE_PRINCIPAL, false,
                "uri=" + request.getRequestURI(), request.getRemoteAddr()).withReason("token.rejected")
                .unverifiedActor());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
}

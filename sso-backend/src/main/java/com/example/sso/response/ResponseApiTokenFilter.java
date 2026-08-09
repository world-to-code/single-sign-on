package com.example.sso.response;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.ratelimit.RateLimit;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
 * <p><b>A machine.</b> Proven by {@code sub == aud}, which is what this authorization server mints for a
 * {@code client_credentials} token and only for one — a user's token carries a username in {@code sub} and
 * the client in {@code aud}. Checking that {@code sub} merely NAMES a machine client was not the same claim:
 * {@code client_id} and {@code username} live in two namespaces an administrator controls, so a client
 * registered under an existing username would have let that person's ordinary token drive this API. The same
 * comparison satisfies RFC 9068 §4's audience requirement, which nothing else here was doing.
 *
 * <p>A correlation id is mandatory on every call. It is what lets an investigator join a hold here to the
 * detection that caused it over there; a response nobody can trace back to a reason is one nobody can
 * evaluate, and by the time somebody asks, the answer has to already be in the row.
 *
 * <p>Every ACTION also spends from the calling client's budget. One XDR false positive must not be able to
 * sign out an estate, and the API has no bulk verb precisely so that a runaway can only ever be a loop — a
 * loop is what a budget stops. Reads are not charged: the danger is in acting, not in looking.
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
    private final ResponseCaller caller;
    private final AuditService audit;
    /** Per-client, so one tenant's runaway detector cannot spend another tenant's allowance. */
    private final RateLimit budget;
    /** The issuer to fall back on when a request carries no Host header (never, in practice, over HTTP/1.1). */
    private final String fallbackIssuer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RegisteredClient client = callingClient(request);
        if (client == null) {
            unauthorized(request, response);
            return;
        }
        String correlationId = correlationId(request);
        if (correlationId == null) {
            // A 400, not a 401: the credential was fine and the CALL was not. Told apart because a response
            // system retrying a 401 re-authenticates forever against a request that will never be accepted.
            audit.record(new AuditRecord(AuditType.RESPONSE_ACTION_REFUSED, RESPONSE_PRINCIPAL, false,
                    "client=" + client.getClientId() + " uri=" + request.getRequestURI(),
                    request.getRemoteAddr()).withReason("correlation.missing"));
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        // Keyed on the client's INTERNAL id, which is globally unique. A client_id is unique only per tenant
        // (V109), so keying on it would put two tenants that both call their detector "xdr" in one bucket —
        // one tenant's runaway would then 429 the other's response API mid-incident.
        if (isAction(request) && !budget.tryAcquire(client.getId())) {
            budgetExhausted(request, response, client.getClientId());
            return;
        }

        caller.bind(client.getClientId(), correlationId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                RESPONSE_PRINCIPAL, null, grantedScopes(request, client)));
        chain.doFilter(request, response);
    }

    /** Anything that is not a read. Looking at a hold costs nothing; ending sessions is what has a bound. */
    private boolean isAction(HttpServletRequest request) {
        return !HttpMethod.GET.matches(request.getMethod());
    }

    /**
     * The breaker, tripped. Recorded CRITICAL and refused with 429 rather than quietly throttled: a response
     * system that has burned its whole allowance is either broken or acting on a detection that has gone
     * wrong, and both are things somebody has to look at now rather than in the morning.
     */
    private void budgetExhausted(HttpServletRequest request, HttpServletResponse response, String clientId)
            throws IOException {
        audit.record(new AuditRecord(AuditType.RESPONSE_BUDGET_EXHAUSTED, RESPONSE_PRINCIPAL, false,
                "client=" + clientId + " uri=" + request.getRequestURI(), request.getRemoteAddr()));
        response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    /** The client behind this call, if the token is one this host issued to a machine client of its tenant. */
    private RegisteredClient callingClient(HttpServletRequest request) {
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
        return issuedToItsOwnClient(jwt) ? clientOfThisTenant(jwt.getSubject()) : null;
    }

    /**
     * The token's audience is the token's subject — the shape this authorization server gives a
     * {@code client_credentials} token, where the principal IS the client. Also the RFC 9068 §4 audience
     * check: a token minted for some other audience is not spendable here.
     */
    private boolean issuedToItsOwnClient(Jwt jwt) {
        List<String> audience = jwt.getAudience();
        return audience != null && audience.size() == 1 && audience.get(0).equals(jwt.getSubject());
    }

    /**
     * The client this token was minted for, if this tenant owns it and it is a machine client. The repository
     * is org-scoped, so a client belonging to another tenant resolves to nothing rather than to somebody
     * else's client of the same name.
     */
    private RegisteredClient clientOfThisTenant(String clientId) {
        if (clientId == null) {
            return null;
        }
        RegisteredClient client = clients.findByClientId(clientId);
        return client != null
                && client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.CLIENT_CREDENTIALS)
                ? client : null;
    }

    private String correlationId(HttpServletRequest request) {
        String value = request.getHeader(CORRELATION_HEADER);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.length() > MAX_CORRELATION_LENGTH ? null : trimmed;
    }

    /**
     * The scopes this call may act on: the token's, INTERSECTED with the ones the client still holds.
     *
     * <p>Narrowing a client's scopes has to take effect now, not when its longest-lived token runs out.
     * Without the intersection, taking {@code response:session-terminate} away from a misbehaving detector
     * would leave every token it already minted able to end sessions for the rest of its lifetime — a
     * revocation that does not revoke, which is the shape the zero-trust rules name explicitly.
     */
    private List<SimpleGrantedAuthority> grantedScopes(HttpServletRequest request, RegisteredClient client) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (String scope : tokenScopes(request)) {
            if (client.getScopes().contains(scope)) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
            }
        }
        return authorities;
    }

    /** Re-decodes the bearer this request already proved; the alternative is threading the Jwt everywhere. */
    private List<String> tokenScopes(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        Object scope = jwtDecoder.decode(header.substring(BEARER_PREFIX.length()).trim()).getClaim("scope");
        if (scope instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return scope instanceof String single ? List.of(single.split(" ")) : List.of();
    }

    /**
     * The issuer this host mints under.
     *
     * <p>Built from {@code getServerName()}, the SAME value {@code TenantHostFilter} binds the tenant from.
     * Reading the raw {@code Host} header instead would let the two disagree behind a proxy that rewrites
     * one and not the other — and this chain's whole tenant argument is that they cannot.
     */
    private String expectedIssuer(HttpServletRequest request) {
        String host = request.getServerName();
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

package com.example.sso.ratelimit;

import com.example.sso.ratelimit.internal.RateLimiter;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.factor.Factors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Throttles credential-submission AND credential-verification endpoints per client IP to blunt
 * brute-force (passwords, 6-digit TOTP/email codes) and OTT flooding. Exceeding the window
 * yields HTTP 429 and an audit record.
 *
 * <p>The client IP is taken from {@link HttpServletRequest#getRemoteAddr()}. This is only spoof-proof
 * when {@code server.forward-headers-strategy} trusts {@code X-Forwarded-*} ONLY from pinned proxies:
 * dev uses {@code none} (no proxy, real peer); prod uses {@code native} with
 * {@code server.tomcat.remoteip.internal-proxies} set to the load-balancer CIDR. Do NOT use
 * {@code framework} (it trusts XFF unconditionally, letting clients rotate it for a fresh bucket).
 */
@Component
@Slf4j
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS =
            Set.of("/api/auth/identify", "/api/auth/login",
                    // Spends no secret, and was missed for exactly that reason. It is unauthenticated and every
                    // call stashes the chosen tenant — creating a session in Redis and an audit row — so an
                    // unmetered client can sweep the slug namespace for which tenants exist while burying the
                    // security feed. What earns a limit here is the COST, not a credential.
                    "/api/auth/organization",
                    // Mails a one-time code: unlimited requests would let a signed-in user mail-bomb their
                    // own address (and burn the mail quota).
                    "/api/auth/email-verification", "/api/auth/email-verification/confirm",
                    // Texts a one-time code to a caller-supplied number: unthrottled, this is SMS-bombing of an
                    // arbitrary victim and premium/international toll-fraud (direct spend), worse than email.
                    "/api/auth/phone-verification", "/api/auth/phone-verification/confirm",
                    "/api/onboarding/apply", "/api/onboarding/activate", "/api/onboarding/set-password");
    private static final String FACTORS_PREFIX = "/api/auth/factors/"; // .../prepare and .../verify
    private static final String REAUTH_PREFIX = "/api/auth/reauth/";   // step-up / re-auth .../prepare and .../verify
    // Browser-navigation GETs, so the method gate below deliberately does not apply to them. Each start
    // drives an unauthenticated outbound fetch to the tenant's upstream (a slow IdP ties up a servlet thread
    // for the whole timeout), and each callback can create an account and a session. Throttled on what they
    // DO, not on their verb.
    private static final String FEDERATION_PREFIX = "/api/auth/federation/";
    // The machine response API. Unauthenticated here — its own chain authenticates it later — and every call
    // it turns away writes an audit row, into a HASH-CHAINED trail where a write costs more than a log line.
    // That trail is also where an operator would notice this API being probed, which a flood buries. Its verbs
    // are PUT/DELETE/GET, so this cannot hang off the POST gate below: what earns the limit is the COST.
    // Generous against real use — a detection system's own action budget (50/hour per client) is far tighter.
    private static final String RESPONSE_PREFIX = "/api/response/";
    // One accepted file becomes hundreds of accounts, so this is throttled on what it DOES, not on being an
    // authenticated admin route. Keyed on the principal rather than the IP below: the callers are signed-in
    // administrators, often behind one office address, and an IP key would let one of them exhaust the budget
    // for all of them.
    //
    // The KEY drops the variable path but MUST keep the tenant: the route is
    // /api/admin/profiles/{id}/csv-import[/preview], and the profile id was the only thing scoping the bucket to
    // an org. The principal is the per-org username (unique only within a tenant — tenant = organization), so
    // "csv-import:{principal}" alone would collide two same-named admins across tenants, letting a busy admin in
    // one tenant starve the same-named admin in another. The discriminator is (tenant, principal): one budget
    // per administrator, isolated per tenant. The tenant comes from the SESSION's ORG_ marker, not the request
    // host — the host is caller-supplied (spoofable to another tenant's subdomain), the session marker is not.
    private static final String CSV_IMPORT_SEGMENT = "/csv-import";

    private final RateLimiter rateLimiter;
    private final AuditService audit;

    public AuthRateLimitFilter(RateLimiter rateLimiter, AuditService audit) {
        this.rateLimiter = rateLimiter;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isLimited(request.getMethod(), request.getServletPath())) {
            String ip = request.getRemoteAddr();
            String path = request.getServletPath();
            boolean csvImport = path.contains(CSV_IMPORT_SEGMENT);
            String key = csvImport ? csvImportKey(ip) : path + ":" + ip;
            if (!rateLimiter.tryAcquire(key)) {
                // Attribute it to the principal when there IS one: several limited routes are called by a
                // signed-in user, and an audit row carrying only an IP is unattributable — and invisible in
                // the tenant-scoped audit view, which is precisely where an operator would look for it.
                String actor = principal();
                audit.record(new AuditRecord(AuditType.RATE_LIMITED, actor == null ? ip : actor, false,
                        request.getServletPath(), ip));
                // A throttled credential endpoint is a security signal; without a log line the defence works
                // and nobody can tell that it did.
                log.warn("Rate limit reached for {} (actor={}, ip={})", request.getServletPath(),
                        actor == null ? "anonymous" : actor, ip);
                writeProblem(response, request.getServletPath());
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * One bucket per (tenant, administrator) for the whole import feature — fixed segment, tenant from the
     * session's {@code ORG_} marker, principal as the actor. The org discriminator keeps two same-named tenant
     * admins apart; a super carries no marker (they are global, cross-org) and their globally-unique username
     * stands alone. Falls back to the IP only for the unauthenticated impossible-case, so a bucket always exists.
     */
    private String csvImportKey(String ip) {
        String actor = principal();
        return CSV_IMPORT_SEGMENT + ":" + sessionOrg() + ":" + (actor == null ? ip : actor);
    }

    /**
     * The org the caller logged into, taken from the session's {@code ORG_} marker authority (verbatim — the
     * whole marker is a stable per-tenant string). Unlike the request host, this is server-issued at
     * authentication and cannot be spoofed to another tenant. Empty for a super (no marker) and for any
     * unauthenticated caller.
     */
    private String sessionOrg() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "";
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(Factors.ORG_PREFIX))
                .findFirst().orElse("");
    }

    /** The authenticated caller, or null — several limited routes are reachable only while signed in. */
    private String principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())
                ? authentication.getName() : null;
    }

    /**
     * RFC 7807, like every other error this API returns. A bare text body left the client unable to parse the
     * one response it most needs to explain, so a throttled user saw nothing at all.
     */
    private void writeProblem(HttpServletResponse response, String path) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"code\":\"RATE_LIMITED\",\"detail\":\"Too many attempts. Please wait and try again.\","
                + "\"instance\":\"" + path.replace("\"", "") + "\"}");
    }

    /**
     * Whether this request spends a budget.
     *
     * <p>The {@code POST} gate below is not a proxy for "dangerous" — the rule is to limit by what a route DOES
     * ({@code owasp.md} A04), and a route that only exists as a POST answers 405 to anything else, so gating it
     * buys nothing either way. What the gate is actually load-bearing for is
     * {@code GET /api/auth/factors/{factor}/delivery}: the client polls it five times per send to learn that no
     * code is coming, and metering it would 429 the user whose message already failed to arrive.
     *
     * <p>So the gate protects a known-safe GET rather than classifying the POSTs, which means a NEW route that
     * costs something on a GET gets nothing by default. Such a route belongs above this line, method-agnostic
     * and with the cost stated — as the federation endpoints are.
     */
    private boolean isLimited(String method, String path) {
        if (path.startsWith(FEDERATION_PREFIX) || path.startsWith(RESPONSE_PREFIX)) {
            return true;
        }
        return "POST".equalsIgnoreCase(method)
                && (LIMITED_PATHS.contains(path)
                    || path.startsWith(FACTORS_PREFIX)
                    || path.startsWith(REAUTH_PREFIX)
                    || path.contains(CSV_IMPORT_SEGMENT));
    }
}

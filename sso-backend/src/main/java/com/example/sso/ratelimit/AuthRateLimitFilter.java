package com.example.sso.ratelimit;

import com.example.sso.ratelimit.internal.RateLimiter;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
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
            String key = request.getServletPath() + ":" + ip;
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

    private boolean isLimited(String method, String path) {
        if (path.startsWith(FEDERATION_PREFIX)) {
            return true; // method-agnostic: these are GETs by design
        }
        return "POST".equalsIgnoreCase(method)
                && (LIMITED_PATHS.contains(path)
                    || path.startsWith(FACTORS_PREFIX)
                    || path.startsWith(REAUTH_PREFIX));
    }
}

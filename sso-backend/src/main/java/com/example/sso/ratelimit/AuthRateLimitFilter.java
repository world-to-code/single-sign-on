package com.example.sso.ratelimit;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.ratelimit.internal.InMemoryRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS =
            Set.of("/api/auth/identify", "/api/auth/login");
    private static final String FACTORS_PREFIX = "/api/auth/factors/"; // .../prepare and .../verify

    private final InMemoryRateLimiter rateLimiter;
    private final AuditService audit;
    private final int maxAttempts;
    private final long windowMillis;

    public AuthRateLimitFilter(InMemoryRateLimiter rateLimiter,
                               AuditService audit,
                               @Value("${sso.ratelimit.attempts:10}") int maxAttempts,
                               @Value("${sso.ratelimit.window-seconds:60}") long windowSeconds) {
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.maxAttempts = maxAttempts;
        this.windowMillis = windowSeconds * 1000L;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod()) && isLimited(request.getServletPath())) {
            String ip = request.getRemoteAddr();
            String key = request.getServletPath() + ":" + ip;
            if (!rateLimiter.tryAcquire(key, maxAttempts, windowMillis, System.currentTimeMillis())) {
                audit.record(new AuditRecord("RATE_LIMITED", ip, false, request.getServletPath(), ip));
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.getWriter().write("Too many requests. Please retry later.");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isLimited(String path) {
        return LIMITED_PATHS.contains(path) || path.startsWith(FACTORS_PREFIX);
    }
}

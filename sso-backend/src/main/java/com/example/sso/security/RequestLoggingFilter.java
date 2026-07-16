package com.example.sso.security;

import com.example.sso.shared.web.ClientIp;
import com.example.sso.tenancy.OrgContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-request access log + MDC enrichment — the backbone of request-flow tracing. Registered to run INSIDE the
 * security chain (after it establishes the {@code SecurityContext} and binds {@link OrgContext}), so it can put
 * the acting {@code user}, tenant {@code org} and {@code clientIp} into the SLF4J MDC BEFORE the handler runs —
 * every downstream log line (service, repository) then carries them, alongside the {@code traceId}/{@code spanId}
 * Micrometer Tracing injects. On completion it emits one INFO access line ({@code method path status durationMs})
 * on a dedicated {@code com.example.sso.access} logger, so the whole line set for one request is greppable by
 * traceId and an operator can see who did what, in which tenant, with what result and latency.
 *
 * <p>Health probes and static SPA assets are skipped to keep the log signal about API traffic. A
 * security-chain rejection that never reaches this filter (e.g. a pre-auth 401) is not access-logged here; the
 * error/audit path ({@code ServerErrorAuditFilter}, {@code GlobalExceptionHandler}) carries those.
 */
@RequiredArgsConstructor
public class RequestLoggingFilter extends OncePerRequestFilter {

    /** Dedicated logger so the access log can be tuned (or silenced) independently of application logging. */
    private static final Logger access = LoggerFactory.getLogger("com.example.sso.access");

    static final String MDC_USER = "user";
    static final String MDC_ORG = "org";
    static final String MDC_CLIENT_IP = "clientIp";

    private final OrgContext orgContext;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health") || isStaticAsset(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long startNanos = System.nanoTime();
        MDC.put(MDC_CLIENT_IP, ClientIp.of(request));
        MDC.put(MDC_USER, currentUser());
        MDC.put(MDC_ORG, currentOrg());
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - startNanos) / 1_000_000L;
            access.info("{} {} {} {}ms", request.getMethod(), request.getRequestURI(), response.getStatus(), millis);
            MDC.remove(MDC_CLIENT_IP);
            MDC.remove(MDC_USER);
            MDC.remove(MDC_ORG);
        }
    }

    private String currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() ? authentication.getName() : "anonymous";
    }

    private String currentOrg() {
        return orgContext.currentOrg().map(id -> id.toString()).orElse(orgContext.isPlatform() ? "platform" : "-");
    }

    /** The SPA is served from the same origin; its asset/route requests are not API traffic worth access-logging. */
    private boolean isStaticAsset(String path) {
        return path.equals("/") || path.startsWith("/assets/") || path.equals("/favicon.ico")
                || path.equals("/index.html") || path.startsWith("/static/");
    }
}

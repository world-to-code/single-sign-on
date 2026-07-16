package com.example.sso.security;

import com.example.sso.shared.web.ClientIp;
import com.example.sso.shared.web.RequestTrace;
import com.example.sso.tenancy.OrgContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-request access log + MDC enrichment — the backbone of request-flow tracing. Registered to run INSIDE the
 * security chain (after it establishes the {@code SecurityContext} and binds {@link OrgContext}), so it can put
 * the acting {@code user}, tenant {@code org} and {@code clientIp} into the SLF4J MDC BEFORE the handler runs —
 * every downstream log line (service, repository) then carries them, alongside the {@code traceId}/{@code spanId}
 * Micrometer Tracing injects. On completion it emits ONE access line ({@code method path status durationMs}) on a
 * dedicated {@code com.example.sso.access} logger, with the method/status/latency ALSO as structured MDC fields
 * so a log aggregator can query them (e.g. {@code http.status:500}) without regex on the message. The level
 * scales with the outcome — {@code 5xx=ERROR}, {@code 4xx=WARN}, else {@code INFO} — so failures stand out and an
 * operator can raise the logger to WARN to see only problems.
 *
 * <p>User-controlled values (client IP from {@code X-Forwarded-For}, the username) are stripped of control
 * characters before logging, so a crafted header cannot forge a log line (log injection) on the plain-text dev
 * console; the prod ECS-JSON encoder escapes them anyway.
 *
 * <p>Health probes and static SPA assets are skipped to keep the log signal about API traffic. A security-chain
 * rejection that never reaches this filter (e.g. a pre-auth 401) is not access-logged here; the error/audit path
 * ({@code ServerErrorAuditFilter}, {@code GlobalExceptionHandler}) carries those.
 */
@RequiredArgsConstructor
public class RequestLoggingFilter extends OncePerRequestFilter {

    /** Dedicated logger so the access log can be tuned (or silenced) independently of application logging. */
    private static final Logger access = LoggerFactory.getLogger("com.example.sso.access");

    /** Control chars + the Unicode line/paragraph separators (U+0085/2028/2029) a plain-text log could split on. */
    private static final Pattern FORGERY_CHARS = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");

    static final String MDC_USER = "user";
    static final String MDC_ORG = "org";
    static final String MDC_CLIENT_IP = "clientIp";
    static final String MDC_HTTP_METHOD = "http.method";
    static final String MDC_HTTP_STATUS = "http.status";
    static final String MDC_HTTP_DURATION = "http.duration_ms";

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
        RequestTrace.bind(request); // capture the trace id so an outer error filter reads the SAME id as the logs
        MDC.put(MDC_CLIENT_IP, clean(ClientIp.of(request)));
        MDC.put(MDC_USER, clean(currentUser()));
        MDC.put(MDC_ORG, currentOrg());
        MDC.put(MDC_HTTP_METHOD, request.getMethod());
        int status = response.getStatus();
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } catch (IOException | ServletException | RuntimeException e) {
            status = HttpStatus.INTERNAL_SERVER_ERROR.value(); // an error escaping the chain renders 500 downstream
            throw e;
        } finally {
            long millis = (System.nanoTime() - startNanos) / 1_000_000L;
            MDC.put(MDC_HTTP_STATUS, Integer.toString(status));
            MDC.put(MDC_HTTP_DURATION, Long.toString(millis));
            logAccess(request, status, millis);
            MDC.remove(MDC_CLIENT_IP);
            MDC.remove(MDC_USER);
            MDC.remove(MDC_ORG);
            MDC.remove(MDC_HTTP_METHOD);
            MDC.remove(MDC_HTTP_STATUS);
            MDC.remove(MDC_HTTP_DURATION);
        }
    }

    /** One access line whose level reflects the outcome — 5xx errors and 4xx client faults stand apart from 2xx. */
    private void logAccess(HttpServletRequest request, int status, long millis) {
        String format = "{} {} {} {}ms";
        String method = request.getMethod();
        String path = request.getRequestURI(); // path only — never the query string (it can carry tokens/secrets)
        if (status >= 500) {
            access.error(format, method, path, status, millis);
        } else if (status >= 400) {
            access.warn(format, method, path, status, millis);
        } else {
            access.info(format, method, path, status, millis);
        }
    }

    private String currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() ? authentication.getName() : "anonymous";
    }

    private String currentOrg() {
        return orgContext.currentOrg().map(id -> id.toString()).orElse(orgContext.isPlatform() ? "platform" : "-");
    }

    /** Strip control/separator characters (incl. CR/LF) so a user-controlled value can't forge a line in a log. */
    private String clean(String value) {
        return value == null ? null : FORGERY_CHARS.matcher(value).replaceAll("");
    }

    /** The SPA is served from the same origin; its asset/route requests are not API traffic worth access-logging. */
    private boolean isStaticAsset(String path) {
        return path.equals("/") || path.startsWith("/assets/") || path.equals("/favicon.ico")
                || path.equals("/index.html") || path.startsWith("/static/");
    }
}

package com.example.sso.audit;

import com.example.sso.shared.web.RequestTrace;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Outermost filter that turns an otherwise-opaque server error into something operable:
 * <ul>
 *   <li>writes a {@code SERVER_ERROR} entry to the audit log with a short reference id, the request, and the
 *       root cause, and</li>
 *   <li>returns a clean JSON body carrying that reference id — no stack trace is leaked to the client,
 *       and the user can quote the reference to an administrator who looks it up in the audit log.</li>
 * </ul>
 * Only triggers on exceptions that escape the entire chain (i.e. would have been a raw 500); normal
 * OAuth2/validation error responses are produced inside the chain and pass through untouched.
 *
 * <p><b>The detail is for THIS system's operators and does not leave it.</b> A root cause message is
 * uncontrolled text — a unique-violation quotes the value that collided, which is routinely somebody's email
 * address — and the frames describe our own internals. That was acceptable while the trail was only readable
 * through the admin API; it stopped being so when the trail gained an exit to a third party, so the exporter
 * drops this detail and ships the exception TYPE and the reference instead. Both are enough to correlate; the
 * operator who needs the rest reads it here.
 */
@RequiredArgsConstructor
public class ServerErrorAuditFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ServerErrorAuditFilter.class);
    private static final int MAX_DETAIL = 2000;
    private static final int STACK_FRAMES = 8;

    private final AuditService audit;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (Exception ex) {
            // The request's trace id (bound at request start) — the SAME id the access log and the client's error
            // response carry, so the 500's log line, its audit row and the user's reference are ONE identifier.
            String ref = RequestTrace.of(request);
            log.error("Unhandled server error ref={} {} {}", ref, request.getMethod(), request.getRequestURI(), ex);

            recordSafely(ref, request, ex);
            writeCleanResponse(response, ref);
        }
    }

    private void recordSafely(String ref, HttpServletRequest request, Exception ex) {
        try {
            Throwable root = rootCause(ex);
            String detail = truncate(request.getMethod() + " " + request.getRequestURI() + " [" + ref + "] "
                    + root.getClass().getName() + ": " + (root.getMessage() == null ? "" : root.getMessage())
                    + topFrames(root));

            // The exception TYPE goes in the structured reason, not only inside the free-form detail: it is the
            // part that is ours to publish, and keeping it separate is what lets the exporter ship one and not
            // the other without parsing a string this class happens to format.
            audit.record(new AuditRecord(AuditType.SERVER_ERROR, currentPrincipal(), false, detail,
                    request.getRemoteAddr()).withReason(root.getClass().getSimpleName()));
        } catch (RuntimeException auditFailure) {
            log.error("Failed to audit server error ref={}", ref, auditFailure);
        }
    }

    /** The top of the throwing stack so an admin can pinpoint the cause from the audit log alone. */
    private String topFrames(Throwable root) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] frames = root.getStackTrace();
        for (int i = 0; i < Math.min(STACK_FRAMES, frames.length); i++) {
            sb.append("\n  at ").append(frames[i]);
        }
        return sb.toString();
    }

    private void writeCleanResponse(HttpServletResponse response, String ref) throws IOException {
        if (response.isCommitted()) {
            return; // the chain already started streaming a response; nothing safe to do
        }

        response.reset();
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"server_error\","
                + "\"message\":\"An unexpected error occurred. Quote reference " + ref + " to an administrator.\","
                + "\"reference\":\"" + ref + "\"}");
    }

    private String currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() ? authentication.getName() : "anonymous";
    }

    private Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private String truncate(String s) {
        return s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL);
    }
}

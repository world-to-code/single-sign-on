package com.example.sso.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.MDC;

/**
 * The single trace id for a request, so the id shown to a client in an error response is the SAME one an
 * operator finds in the logs. Micrometer Tracing puts the current trace id in the SLF4J MDC while the request's
 * observation scope is open; an OUTER error filter (e.g. {@code ServerErrorAuditFilter}) runs AFTER that scope
 * has closed, where the MDC is already cleared — so the id is captured onto a request attribute at request start
 * ({@link #bind}) and every consumer reads that same value ({@link #of}).
 */
public final class RequestTrace {

    /** Request attribute holding the request's trace id, bound at request start by the request-logging filter. */
    public static final String ATTRIBUTE = "com.example.sso.trace.id";
    /** The MDC key Micrometer Tracing populates with the current trace id. */
    private static final String MDC_TRACE_ID = "traceId";

    private RequestTrace() {
    }

    /**
     * Capture the current trace id onto the request so outer filters can read it after the trace scope ends.
     * Stores ONLY when Micrometer has a REAL trace id in the MDC — if this runs before the trace scope opens (a
     * filter-ordering slip), it binds nothing rather than pinning a random fallback that {@link #of} would then
     * wrongly prefer over the real id that appears moments later.
     */
    public static void bind(HttpServletRequest request) {
        String traceId = MDC.get(MDC_TRACE_ID);
        if (traceId != null && !traceId.isBlank()) {
            request.setAttribute(ATTRIBUTE, traceId);
        }
    }

    /**
     * The request's trace id: the bound value, else the live MDC value, else a generated fallback. A generated
     * fallback is PINNED back onto the request, so a second call within the same request (e.g. once for an error
     * response, once for its log line) returns the SAME id — otherwise the id handed to the client would differ
     * from the one in the logs and the correlation would be lost.
     */
    public static String of(HttpServletRequest request) {
        if (request == null) {
            return current(); // no request to stabilise on — an error raised outside any request scope
        }
        Object bound = request.getAttribute(ATTRIBUTE);
        if (bound instanceof String s && !s.isBlank()) {
            return s;
        }
        String id = current();
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }

    private static String current() {
        String traceId = MDC.get(MDC_TRACE_ID);
        return traceId != null && !traceId.isBlank() ? traceId : generated();
    }

    /**
     * Fallback for an error raised OUTSIDE any active trace. Intentionally 16 hex — HALF the width of
     * Micrometer's 32-hex W3C trace id — so a 16-char id in the logs is an immediate signal that no trace was
     * active. Do NOT widen it to 32 to "match": the differing length is the tell.
     */
    private static String generated() {
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }
}

package com.example.sso.audit.internal.domain;

/**
 * Client context captured from the current request: the source IP, the raw User-Agent, a
 * display-only device label derived from it, and the request/correlation id (W3C trace id) that
 * ties this event to the rest of the request's telemetry. All null when no request is bound (an
 * async sweep or an AFTER_COMMIT listener records off the request thread).
 */
public record AuditClientInfo(String ip, String userAgent, String device, String requestId) {

    public static final AuditClientInfo NONE = new AuditClientInfo(null, null, null, null);
}

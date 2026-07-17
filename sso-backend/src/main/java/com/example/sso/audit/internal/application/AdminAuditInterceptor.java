package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditActor;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.Audited;
import com.example.sso.shared.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records an admin-action audit event for every handler marked {@link Audited}, after it completes. Runs on
 * {@code /api/admin/**}; a non-marked handler (or a non-handler resource) is ignored. The outcome is derived
 * from the response — {@code success} unless the handler threw or the status is 4xx/5xx — so a denied or
 * invalid privileged attempt is recorded too. The org is left null so {@code AuditService} stamps the acting
 * tenant, exactly as the service-layer {@code AdminAuditLogger} path does. The audit write is
 * {@code REQUIRES_NEW} inside the service, so it can never roll back the business transaction.
 */
@Component
@RequiredArgsConstructor
public class AdminAuditInterceptor implements HandlerInterceptor {

    private final AuditService audit;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        if (!(handler instanceof HandlerMethod method)) {
            return;
        }
        Audited audited = method.getMethodAnnotation(Audited.class);
        if (audited == null) {
            return;
        }
        boolean success = ex == null && response.getStatus() < 400;
        String detail = request.getMethod() + " " + request.getRequestURI();
        AuditRecord record = new AuditRecord(audited.value(), AuditActor.of(), success, detail,
                ClientIp.of(request), audited.subject(), subjectId(request, audited.subjectParam()), null);
        audit.record(success ? record : record.withReason(failureReason(response, ex)));
    }

    /** A structured reason for a blocked/failed privileged attempt (never the exception message — no leakage). */
    private String failureReason(HttpServletResponse response, Exception ex) {
        return ex != null ? "exception: " + ex.getClass().getSimpleName() : "http " + response.getStatus();
    }

    /** The acted-on target id from the URI path variable named by {@code subjectParam}, or null if unset. */
    private String subjectId(HttpServletRequest request, String subjectParam) {
        if (subjectParam.isBlank()) {
            return null;
        }
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return vars instanceof Map<?, ?> map ? (String) map.get(subjectParam) : null;
    }
}

package com.example.sso.audit.internal.application;

import com.example.sso.audit.internal.domain.AuditClientInfo;
import com.example.sso.shared.web.ClientIp;
import com.example.sso.shared.web.DeviceLabeler;
import com.example.sso.shared.web.RequestTrace;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Captures client context (IP, User-Agent, device label, correlation id) from the request bound to
 * the current thread. The audit write runs in a nested transaction on the same request thread, so
 * the request is still reachable; off-request recorders (async sweeps, AFTER_COMMIT listeners) get
 * {@link AuditClientInfo#NONE}.
 */
@Component
@RequiredArgsConstructor
public class AuditClientResolver {

    private final DeviceLabeler deviceLabeler;

    public AuditClientInfo capture() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return AuditClientInfo.NONE;
        }
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        return new AuditClientInfo(ClientIp.of(request), userAgent,
                deviceLabeler.label(userAgent), RequestTrace.of(request));
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}

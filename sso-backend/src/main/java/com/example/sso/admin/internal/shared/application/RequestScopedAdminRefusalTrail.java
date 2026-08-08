package com.example.sso.admin.internal.shared.application;

import com.example.sso.admin.AdminRefusal;
import com.example.sso.admin.AdminRefusalTrail;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Keeps the last refusal on the current request.
 *
 * <p>Request-scoped rather than a field: guards run on the request thread during authorization, and a shared
 * field would let one request's refusal explain another's denial — a wrong reason is worse than none.
 *
 * <p>Off a request (the async re-validation paths call the same policy) there is nowhere to record and
 * nothing that will read it, so both sides degrade to doing nothing.
 */
@Component
class RequestScopedAdminRefusalTrail implements AdminRefusalTrail {

    private static final String ATTRIBUTE = RequestScopedAdminRefusalTrail.class.getName() + ".lastRefusal";

    void record(AdminRefusal refusal) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(ATTRIBUTE, refusal, RequestAttributes.SCOPE_REQUEST);
        }
    }

    @Override
    public Optional<AdminRefusal> lastRefusal() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes == null ? Optional.empty()
                : Optional.ofNullable(attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST))
                        .filter(AdminRefusal.class::isInstance).map(AdminRefusal.class::cast);
    }
}

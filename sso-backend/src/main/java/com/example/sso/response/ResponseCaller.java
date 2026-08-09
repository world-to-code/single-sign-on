package com.example.sso.response;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Who is calling the response API on this request, and under which detection.
 *
 * <p>Request-scoped rather than a field, for the reason the admin refusal trail is: these run on the request
 * thread, and a shared field would let one caller's identity be recorded against another's action — a
 * wrong attribution is worse than none, because it points an investigation at the wrong system.
 *
 * <p>Two facts that travel together into the trail: the correlation id points OUT at the detection that
 * decided this, the client id points IN at the credential that acted. After a leak, "which detection" without
 * "which system" cannot answer the only question that matters — which credential to revoke.
 */
@Component
public class ResponseCaller {

    private static final String CORRELATION = ResponseCaller.class.getName() + ".correlationId";
    private static final String CLIENT = ResponseCaller.class.getName() + ".clientId";

    void bind(String clientId, String correlationId) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(CLIENT, clientId, RequestAttributes.SCOPE_REQUEST);
            attributes.setAttribute(CORRELATION, correlationId, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** The detection this action belongs to, or empty off a response request — where nothing will ask. */
    public Optional<String> correlationId() {
        return attribute(CORRELATION);
    }

    /** The credential that acted. An investigation that cannot name it cannot decide what to revoke. */
    public Optional<String> clientId() {
        return attribute(CLIENT);
    }

    private Optional<String> attribute(String name) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes == null ? Optional.empty()
                : Optional.ofNullable(attributes.getAttribute(name, RequestAttributes.SCOPE_REQUEST))
                        .map(String::valueOf);
    }
}

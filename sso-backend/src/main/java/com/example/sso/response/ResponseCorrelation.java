package com.example.sso.response;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The caller's correlation id for the response action being performed on this request.
 *
 * <p>Request-scoped rather than a field, for the reason the admin refusal trail is: these run on the request
 * thread, and a shared field would let one detection's id be recorded against another's action — a wrong
 * correlation is worse than none, because it points an investigation at the wrong event.
 *
 * <p>Public because the record it ends up in is written elsewhere: the hold's audit row comes from a domain
 * event in {@code user}, and the id has to reach it without every layer in between passing it along.
 */
@Component
public class ResponseCorrelation {

    private static final String ATTRIBUTE = ResponseCorrelation.class.getName() + ".correlationId";

    void bind(String correlationId) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(ATTRIBUTE, correlationId, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** The id the caller supplied, or empty off a response request — where nothing will ask. */
    public Optional<String> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes == null ? Optional.empty()
                : Optional.ofNullable(attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST))
                        .map(String::valueOf);
    }
}

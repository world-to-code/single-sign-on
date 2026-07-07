package com.example.sso.auth.internal.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stashes the customer-first entry's resolved customer (고객사) in the pre-auth HTTP session (there is no
 * {@code Authentication} yet), so the subsequent identify step can gate on customer-admin membership and login
 * completion can mint the {@code CUSTOMER_} marker (a customer-console session). Parallel to
 * {@link PreAuthOrgSession}; the two are mutually exclusive — an entry clears the other so a session is only
 * ever a customer login OR an org login, never both.
 */
@Component
class PreAuthCustomerSession {

    static final String CUSTOMER_ID = "AUTH_CUSTOMER_ID";
    static final String CUSTOMER_SLUG = "AUTH_CUSTOMER_SLUG";

    void stash(HttpServletRequest request, UUID id, String slug) {
        HttpSession session = request.getSession(true);
        session.setAttribute(CUSTOMER_ID, id.toString());
        session.setAttribute(CUSTOMER_SLUG, slug);
    }

    Optional<UUID> customerId(HttpServletRequest request) {
        return attribute(request, CUSTOMER_ID).map(UUID::fromString);
    }

    Optional<String> customerSlug(HttpServletRequest request) {
        return attribute(request, CUSTOMER_SLUG);
    }

    /** Removes any stashed customer selection (an org entry clears it, so the two never coexist). */
    void clear(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(CUSTOMER_ID);
            session.removeAttribute(CUSTOMER_SLUG);
        }
    }

    private Optional<String> attribute(HttpServletRequest request, String key) {
        HttpSession session = request.getSession(false);
        return session == null ? Optional.empty() : Optional.ofNullable((String) session.getAttribute(key));
    }
}

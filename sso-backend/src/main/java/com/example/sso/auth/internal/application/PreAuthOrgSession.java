package com.example.sso.auth.internal.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stashes the tenant-first entry's resolved organization in the pre-auth HTTP session (there is no
 * {@code Authentication} yet), so the subsequent identify step can gate on membership and login
 * completion can mint the {@code ORG_} marker. Stores the immutable id (for the gate/marker) and the
 * slug (for the SPA's display).
 */
@Component
public class PreAuthOrgSession {

    static final String ORG_ID = "AUTH_ORG_ID";
    static final String ORG_SLUG = "AUTH_ORG_SLUG";

    void stash(HttpServletRequest request, UUID id, String slug) {
        HttpSession session = request.getSession(true);
        session.setAttribute(ORG_ID, id.toString());
        session.setAttribute(ORG_SLUG, slug);
    }

    public Optional<UUID> orgId(HttpServletRequest request) {
        return attribute(request, ORG_ID).map(UUID::fromString);
    }

    Optional<String> orgSlug(HttpServletRequest request) {
        return attribute(request, ORG_SLUG);
    }

    /** Removes any stashed org selection (a customer entry clears it, so the two never coexist). */
    void clear(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ORG_ID);
            session.removeAttribute(ORG_SLUG);
        }
    }

    private Optional<String> attribute(HttpServletRequest request, String key) {
        HttpSession session = request.getSession(false);
        return session == null ? Optional.empty() : Optional.ofNullable((String) session.getAttribute(key));
    }
}

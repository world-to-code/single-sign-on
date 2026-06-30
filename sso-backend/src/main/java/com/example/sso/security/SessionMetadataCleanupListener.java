package com.example.sso.security;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

/**
 * Removes a session's device metadata from the {@link SessionMetadataStore} when the servlet
 * container destroys the session (logout, expiry, or invalidation). Registered as a servlet
 * listener alongside Spring Security's {@code HttpSessionEventPublisher} in {@code SecurityConfig}.
 */
public class SessionMetadataCleanupListener implements HttpSessionListener {

    private final SessionMetadataStore store;

    public SessionMetadataCleanupListener(SessionMetadataStore store) {
        this.store = store;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        store.remove(event.getSession().getId());
    }
}

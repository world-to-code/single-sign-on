package com.example.sso.security;

import com.example.sso.session.SessionMetadataStore;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import lombok.RequiredArgsConstructor;

/**
 * Removes a session's device metadata from the {@link SessionMetadataStore} when the servlet
 * container destroys the session (logout, expiry, or invalidation). Registered as a servlet
 * listener alongside Spring Security's {@code HttpSessionEventPublisher} in {@code SecurityConfig}.
 */
@RequiredArgsConstructor
public class SessionMetadataCleanupListener implements HttpSessionListener {

    private final SessionMetadataStore store;

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        store.remove(event.getSession().getId());
    }
}

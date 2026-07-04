package com.example.sso.security;

import com.example.sso.session.SessionMetadataStore;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.session.events.SessionDestroyedEvent;
import org.springframework.stereotype.Component;

/**
 * Removes a session's device metadata from the {@link SessionMetadataStore} when its Redis session is
 * destroyed. Listens to Spring Session's {@code SessionDestroyedEvent} (the common supertype of
 * deleted + expired), NOT a servlet {@code HttpSessionListener} — Redis sessions are not container-managed,
 * so servlet destroy events never fire; the application event also covers TTL expiry with no request.
 */
@Component
@RequiredArgsConstructor
public class SessionMetadataCleanupListener {

    private final SessionMetadataStore store;

    @EventListener
    public void onSessionDestroyed(SessionDestroyedEvent event) {
        store.remove(event.getSessionId());
    }
}

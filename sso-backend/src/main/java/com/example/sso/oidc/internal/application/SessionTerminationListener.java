package com.example.sso.oidc.internal.application;

import com.example.sso.authpolicy.Factors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.events.SessionDestroyedEvent;
import org.springframework.stereotype.Component;

/**
 * The single trigger for OIDC back-channel logout: fires on ANY Redis session destruction — explicit
 * logout, idle/absolute expiry (via keyspace notification, no request), and concurrent eviction — so all
 * paths converge here. The mandatory-reauth challenge does NOT invalidate the session, so it stays silent.
 * Reads the OP session id from the terminated session's SecurityContext ({@code SID_} marker) and fans out.
 */
@Component
@RequiredArgsConstructor
public class SessionTerminationListener {

    private final LogoutPropagation propagation;

    @EventListener
    public void onSessionDestroyed(SessionDestroyedEvent event) {
        Session session = event.getSession();
        if (session == null) {
            return;
        }
        SecurityContext context = session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context == null || context.getAuthentication() == null) {
            return; // an unauthenticated/anonymous session — nothing to propagate
        }
        String username = context.getAuthentication().getName();
        context.getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(Factors.SID_PREFIX))
                .findFirst()
                .ifPresent(a -> propagation.propagate(a.substring(Factors.SID_PREFIX.length()), username));
    }
}

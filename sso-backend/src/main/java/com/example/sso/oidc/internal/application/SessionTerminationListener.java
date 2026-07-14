package com.example.sso.oidc.internal.application;

import com.example.sso.authpolicy.factor.Factors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class SessionTerminationListener {

    private final LogoutPropagation propagation;

    @EventListener
    public void onSessionDestroyed(SessionDestroyedEvent event) {
        try {
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
        } catch (RuntimeException e) {
            // Isolate this protocol: the default (synchronous, no-errorHandler) multicaster stops invoking the
            // remaining listeners for an event once one throws, so an unexpected failure here (a Redis/DB blip in
            // the index/issuer lookup, say) would also skip the sibling SAML SLO listener for the SAME terminated
            // session. The session is already gone; log so the missed back-channel logout is observable, never
            // silently swallowed by the event infrastructure.
            log.error("OIDC back-channel logout propagation failed for a terminated session", e);
        }
    }
}

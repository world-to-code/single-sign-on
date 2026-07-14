package com.example.sso.saml.internal.logout.application;

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
 * Fires SAML back-channel SLO on any Redis session destruction (logout, idle/absolute expiry, concurrent
 * eviction), reading the OP session id from the terminated session's SecurityContext ({@code SID_} marker).
 * A sibling of the OIDC back-channel listener — one listener per protocol on the same event.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SamlSloListener {

    private final SamlLogoutPropagation propagation;

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
                return;
            }
            String username = context.getAuthentication().getName();
            context.getAuthentication().getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith(Factors.SID_PREFIX))
                    .findFirst()
                    .ifPresent(a -> propagation.propagate(a.substring(Factors.SID_PREFIX.length()), username));
        } catch (RuntimeException e) {
            // Isolate this protocol from the sibling OIDC back-channel listener on the same event: the default
            // synchronous multicaster (no errorHandler) stops on the first throw, so an unexpected failure here
            // would suppress the other protocol's propagation for the SAME terminated session. The session is
            // already gone; log the missed SLO rather than let the event infrastructure swallow it silently.
            log.error("SAML back-channel SLO propagation failed for a terminated session", e);
        }
    }
}

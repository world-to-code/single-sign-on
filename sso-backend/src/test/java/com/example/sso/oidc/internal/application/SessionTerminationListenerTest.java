package com.example.sso.oidc.internal.application;

import com.example.sso.authpolicy.factor.Factors;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.MapSession;
import org.springframework.session.events.SessionDeletedEvent;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The termination listener must propagate using the {@code sid} marker from the destroyed session's
 * SecurityContext, and stay silent for sessions that were never fully authenticated (no marker/context).
 */
@ExtendWith(MockitoExtension.class)
class SessionTerminationListenerTest {

    @Mock
    LogoutPropagation propagation;
    @InjectMocks
    SessionTerminationListener listener;

    private SessionDeletedEvent deletionOf(MapSession session) {
        return new SessionDeletedEvent(this, session);
    }

    @Test
    void propagatesUsingTheSidMarkerFromTheContext() {
        MapSession session = new MapSession();
        var auth = UsernamePasswordAuthenticationToken.authenticated("bob", null,
                List.of(new SimpleGrantedAuthority(Factors.MFA_COMPLETE),
                        new SimpleGrantedAuthority(Factors.SID_PREFIX + "sid-xyz")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(auth));

        listener.onSessionDestroyed(deletionOf(session));

        verify(propagation).propagate("sid-xyz", "bob");
    }

    @Test
    void aPropagationFailureIsContainedNotRethrown() {
        // A throw would stop the (synchronous, no-errorHandler) multicaster from invoking the sibling SAML SLO
        // listener for the SAME terminated session, so the listener must swallow it (logged), never propagate.
        MapSession session = new MapSession();
        var auth = UsernamePasswordAuthenticationToken.authenticated("bob", null,
                List.of(new SimpleGrantedAuthority(Factors.MFA_COMPLETE),
                        new SimpleGrantedAuthority(Factors.SID_PREFIX + "sid-xyz")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(auth));
        doThrow(new RuntimeException("RP back-channel endpoint down")).when(propagation).propagate("sid-xyz", "bob");

        assertThatCode(() -> listener.onSessionDestroyed(deletionOf(session))).doesNotThrowAnyException();
    }

    @Test
    void ignoresSessionsWithNoSecurityContext() {
        listener.onSessionDestroyed(deletionOf(new MapSession()));
        verifyNoInteractions(propagation);
    }

    @Test
    void ignoresAuthenticatedSessionsWithoutASidMarker() {
        MapSession session = new MapSession();
        var auth = UsernamePasswordAuthenticationToken.authenticated("bob", null,
                List.of(new SimpleGrantedAuthority(Factors.MFA_COMPLETE)));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(auth));

        listener.onSessionDestroyed(deletionOf(session));

        verifyNoInteractions(propagation);
    }
}

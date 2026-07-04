package com.example.sso.saml.internal.application;

import com.example.sso.authpolicy.Factors;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The SAML SLO listener must propagate using the {@code sid} marker from the destroyed session's
 * SecurityContext, and stay silent for sessions that were never fully authenticated (no marker/context).
 */
@ExtendWith(MockitoExtension.class)
class SamlSloListenerTest {

    @Mock
    SamlLogoutPropagation propagation;
    @InjectMocks
    SamlSloListener listener;

    private SessionDeletedEvent deletionOf(MapSession session) {
        return new SessionDeletedEvent(this, session);
    }

    @Test
    void propagatesUsingTheSidMarker() {
        MapSession session = new MapSession();
        var auth = UsernamePasswordAuthenticationToken.authenticated("carol", null,
                List.of(new SimpleGrantedAuthority(Factors.MFA_COMPLETE),
                        new SimpleGrantedAuthority(Factors.SID_PREFIX + "sid-9")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(auth));

        listener.onSessionDestroyed(deletionOf(session));

        verify(propagation).propagate("sid-9", "carol");
    }

    @Test
    void ignoresSessionsWithNoSecurityContext() {
        listener.onSessionDestroyed(deletionOf(new MapSession()));
        verifyNoInteractions(propagation);
    }
}

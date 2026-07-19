package com.example.sso.auth.internal.login.application;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.shared.error.BadRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Re-running a login on a completed session rotates the session id without destroying anything, so nothing
 * propagates and whatever the old id indexed — back-channel-logout participants, the SAML SessionIndex — is
 * orphaned and can no longer be logged out. Every login entry point asks this guard the same question.
 */
@ExtendWith(MockitoExtension.class)
class CompletedSessionGuardTest {

    @Mock private CurrentUserProvider currentUser;

    private CompletedSessionGuard guard() {
        return new CompletedSessionGuard(currentUser);
    }

    @Test
    void refusesASessionThatHasCompletedItsLogin() {
        when(currentUser.authentication()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                "bob", null, List.of(new SimpleGrantedAuthority(Factors.MFA_COMPLETE))));

        assertThatThrownBy(() -> guard().refuseIfAlreadySignedIn())
                .isInstanceOf(BadRequestException.class);
    }

    /** A half-authenticated session is MID-login, not past it — it must be allowed to finish. */
    @Test
    void allowsASessionThatHasIdentifiedButNotFinished() {
        when(currentUser.authentication()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                "bob", null, List.of(new SimpleGrantedAuthority(Factors.PASSWORD))));

        assertThatCode(() -> guard().refuseIfAlreadySignedIn()).doesNotThrowAnyException();
    }

    @Test
    void allowsAnAnonymousSession() {
        when(currentUser.authentication()).thenReturn(new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatCode(() -> guard().refuseIfAlreadySignedIn()).doesNotThrowAnyException();
    }

    @Test
    void allowsASessionWithNoAuthenticationAtAll() {
        when(currentUser.authentication()).thenReturn(null);

        assertThatCode(() -> guard().refuseIfAlreadySignedIn()).doesNotThrowAnyException();
    }
}

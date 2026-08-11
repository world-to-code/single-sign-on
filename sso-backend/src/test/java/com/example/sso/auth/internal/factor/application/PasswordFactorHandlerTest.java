package com.example.sso.auth.internal.factor.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link PasswordFactorHandler}: enrollment and verification both delegate to the user
 * module. The delegation is the unit's job, exercised through the returned boolean.
 */
@ExtendWith(MockitoExtension.class)
class PasswordFactorHandlerTest {

    @Mock private UserService userService;
    @Mock private UserAccount user;

    private PasswordFactorHandler handler() {
        return new PasswordFactorHandler(userService);
    }

    @Test
    void factorIsPassword() {
        assertThat(handler().factor()).isEqualTo(AuthFactor.PASSWORD);
    }

    @Test
    void isEnrolledReflectsWhetherTheUserHasAPassword() {
        UUID id = UUID.randomUUID();
        when(user.getId()).thenReturn(id);
        when(userService.hasPassword(id)).thenReturn(true);

        assertThat(handler().isEnrolled(user)).isTrue();
    }

    @Test
    void verifyDelegatesToTheUserPasswordCheckByIdNotUsername() {
        // By id (not username): step-up re-authenticates the ALREADY-resolved principal, so it must not depend
        // on the resolution org being the user's org (which broke password step-up for tenant admins).
        UUID id = UUID.randomUUID();
        when(user.getId()).thenReturn(id);
        when(userService.verifyPassword(id, "secret")).thenReturn(true);

        FactorVerificationResult result = handler().verify(user, new FactorVerificationRequest(null, "secret", null),
                new MockHttpServletRequest());

        assertThat(result.granted()).isTrue();
    }

    /** "Incorrect code" was the answer to a wrong PASSWORD, on a screen with no code on it. */
    @Test
    void verifyReportsAWrongPasswordAsAPasswordFailureNotACodeOne() {
        UUID id = UUID.randomUUID();
        when(user.getId()).thenReturn(id);
        when(userService.verifyPassword(id, "wrong")).thenReturn(false);

        FactorVerificationResult result = handler().verify(user, new FactorVerificationRequest(null, "wrong", null),
                new MockHttpServletRequest());

        assertThat(result.failure()).isEqualTo(FactorFailure.INCORRECT_PASSWORD);
        assertThat(result.messageKey()).isEqualTo("auth.password.incorrect");
    }
}

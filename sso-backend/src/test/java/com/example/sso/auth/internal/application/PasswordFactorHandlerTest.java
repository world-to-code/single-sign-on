package com.example.sso.auth.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
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
    void verifyDelegatesToTheUserPasswordCheck() {
        when(user.getUsername()).thenReturn("alice");
        when(userService.verifyPassword("alice", "secret")).thenReturn(true);

        boolean ok = handler().verify(user, new FactorVerificationRequest(null, "secret", null),
                new MockHttpServletRequest());

        assertThat(ok).isTrue();
    }
}

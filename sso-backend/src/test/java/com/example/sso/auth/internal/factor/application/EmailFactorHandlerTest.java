package com.example.sso.auth.internal.factor.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.mfa.EmailVerificationService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.user.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailFactorHandler}: prepare() emails a code and seeds the session state,
 * verify() enforces the code, its TTL and the max-attempts burn, clearing state on success/burn. The
 * emailing is the collaborator contract, asserted with {@code verify(...)}; the rest asserts on outcome.
 */
@ExtendWith(MockitoExtension.class)
class EmailFactorHandlerTest {

    private static final String CODE = "123456";

    @Mock private EmailVerificationService emails;
    @Mock private UserAccount user;

    private EmailFactorHandler handler;

    @BeforeEach
    void setUp() {
        // TTL 10 min, 3 attempts before the code is burned.
        handler = new EmailFactorHandler(emails, 10, 3);
        lenient().when(user.getEmail()).thenReturn("alice@example.com");
        // The address must be PROVEN before a one-time code is sent to it; the happy-path tests assume it is.
        lenient().when(user.isEmailVerified()).thenReturn(true);
    }

    private FactorVerificationRequest code(String value) {
        return new FactorVerificationRequest(value, null, null);
    }

    @Test
    void factorIsEmail() {
        assertThat(handler.factor()).isEqualTo(AuthFactor.EMAIL);
    }

    @Test
    void prepareGeneratesEmailsAndAcknowledgesTheCode() {
        when(emails.generateCode()).thenReturn(CODE);
        MockHttpServletRequest request = new MockHttpServletRequest();

        FactorChallenge challenge = handler.prepare(user, request);

        assertThat(challenge.prepared()).isTrue();
        verify(emails).sendCode("alice@example.com", CODE);
    }

    @Test
    void verifyWithTheCorrectCodeSucceeds() {
        when(emails.generateCode()).thenReturn(CODE);
        MockHttpServletRequest request = new MockHttpServletRequest();
        handler.prepare(user, request);

        assertThat(handler.verify(user, code(CODE), request)).isTrue();
    }

    @Test
    void verifyWithTheWrongCodeFailsButAllowsARetry() {
        when(emails.generateCode()).thenReturn(CODE);
        MockHttpServletRequest request = new MockHttpServletRequest();
        handler.prepare(user, request);

        assertThat(handler.verify(user, code("000000"), request)).isFalse();
        assertThat(handler.verify(user, code(CODE), request)).isTrue(); // still valid, under the attempt cap
    }

    @Test
    void theCodeIsBurnedAfterTooManyWrongGuesses() {
        EmailFactorHandler strict = new EmailFactorHandler(emails, 10, 2); // burn after 2 wrong guesses
        when(emails.generateCode()).thenReturn(CODE);
        MockHttpServletRequest request = new MockHttpServletRequest();
        strict.prepare(user, request);

        assertThat(strict.verify(user, code("000000"), request)).isFalse();
        assertThat(strict.verify(user, code("111111"), request)).isFalse(); // reaches the cap -> burned
        assertThat(strict.verify(user, code(CODE), request)).isFalse();     // correct, but the code is gone
    }

    @Test
    void anExpiredCodeIsRejected() {
        EmailFactorHandler expired = new EmailFactorHandler(emails, -1, 3); // already past its TTL
        when(emails.generateCode()).thenReturn(CODE);
        MockHttpServletRequest request = new MockHttpServletRequest();
        expired.prepare(user, request);

        assertThat(expired.verify(user, code(CODE), request)).isFalse();
    }

    @Test
    void verifyWithANullCodeReturnsFalse() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(handler.verify(user, code(null), request)).isFalse();
    }

    @Test
    void verifyWithoutAnEstablishedSessionReturnsFalse() {
        MockHttpServletRequest request = new MockHttpServletRequest(); // no session prepared

        assertThat(handler.verify(user, code(CODE), request)).isFalse();
    }

    @Test
    void refusesToSendACodeToAnUnverifiedAddress() {
        // The email is a login identifier an admin can change. Sending a one-time code to an address nobody
        // proved control of — and accepting it — authenticates whoever holds that mailbox.
        when(user.isEmailVerified()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> handler.prepare(user, request)).isInstanceOf(ForbiddenException.class);
        verify(emails, never()).sendCode(any(), any());
    }

    @Test
    void refusesToVerifyACodeOnceTheAddressIsNoLongerVerified() {
        // A code minted before an admin changed the address must not still authenticate.
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(emails.generateCode()).thenReturn(CODE);
        handler.prepare(user, request);

        when(user.isEmailVerified()).thenReturn(false);
        assertThat(handler.verify(user, new FactorVerificationRequest(CODE, null, null), request)).isFalse();
    }
}

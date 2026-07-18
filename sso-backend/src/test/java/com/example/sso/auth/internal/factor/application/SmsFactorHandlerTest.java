package com.example.sso.auth.internal.factor.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.mfa.SmsVerificationService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.user.account.UserAccount;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SmsFactorHandler}: prepare() texts a code and seeds the session state, verify()
 * enforces the code, its TTL and the max-attempts burn, clearing state on success/burn, and both refuse an
 * unverified number. Mirrors {@link EmailFactorHandlerTest}.
 */
@ExtendWith(MockitoExtension.class)
class SmsFactorHandlerTest {

    private static final String CODE = "123456";
    private static final String PHONE = "+14155550123";
    private static final UUID ORG = UUID.randomUUID();

    @Mock private SmsVerificationService sms;
    @Mock private UserAccount user;

    private SmsFactorHandler handler;

    @BeforeEach
    void setUp() {
        // TTL 10 min, 3 attempts before the code is burned.
        handler = new SmsFactorHandler(sms, 10, 3);
        lenient().when(user.getPhoneNumber()).thenReturn(PHONE);
        lenient().when(user.getOrgId()).thenReturn(ORG); // the code is routed through this tenant's relay
        // The number must be PROVEN before a one-time code is sent to it; the happy-path tests assume it is.
        lenient().when(user.isPhoneVerified()).thenReturn(true);
    }

    private FactorVerificationRequest code(String value) {
        return new FactorVerificationRequest(value, null, null);
    }

    @Test
    void factorIsSms() {
        assertThat(handler.factor()).isEqualTo(AuthFactor.SMS);
    }

    @Test
    void prepareGeneratesTextsAndAcknowledgesTheCode() {
        when(sms.generateCode()).thenReturn(CODE);
        MockHttpServletRequest request = new MockHttpServletRequest();

        FactorChallenge challenge = handler.prepare(user, request);

        assertThat(challenge.prepared()).isTrue();
        verify(sms).sendCode(ORG, PHONE, CODE); // via the user's own tenant relay
    }

    @Test
    void verifyWithTheCorrectCodeSucceeds() {
        when(sms.generateCode()).thenReturn(CODE);
        MockHttpServletRequest request = new MockHttpServletRequest();
        handler.prepare(user, request);

        assertThat(handler.verify(user, code(CODE), request)).isTrue();
    }

    @Test
    void verifyWithTheWrongCodeFailsButAllowsARetry() {
        when(sms.generateCode()).thenReturn(CODE);
        MockHttpServletRequest request = new MockHttpServletRequest();
        handler.prepare(user, request);

        assertThat(handler.verify(user, code("000000"), request)).isFalse();
        assertThat(handler.verify(user, code(CODE), request)).isTrue(); // still valid, under the attempt cap
    }

    @Test
    void theCodeIsBurnedAfterTooManyWrongGuesses() {
        SmsFactorHandler strict = new SmsFactorHandler(sms, 10, 2); // burn after 2 wrong guesses
        when(sms.generateCode()).thenReturn(CODE);
        MockHttpServletRequest request = new MockHttpServletRequest();
        strict.prepare(user, request);

        assertThat(strict.verify(user, code("000000"), request)).isFalse();
        assertThat(strict.verify(user, code("111111"), request)).isFalse(); // reaches the cap -> burned
        assertThat(strict.verify(user, code(CODE), request)).isFalse();     // correct, but the code is gone
    }

    @Test
    void anExpiredCodeIsRejected() {
        SmsFactorHandler expired = new SmsFactorHandler(sms, -1, 3); // already past its TTL
        when(sms.generateCode()).thenReturn(CODE);
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
    void refusesToSendACodeToAnUnverifiedNumber() {
        // The number an admin can change, or one changed since enrollment. Texting a one-time code to a number
        // nobody proved control of — and accepting it — authenticates whoever holds that line.
        when(user.isPhoneVerified()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> handler.prepare(user, request)).isInstanceOf(ForbiddenException.class);
        verify(sms, never()).sendCode(any(), any(), any());
    }

    @Test
    void refusesToSendWhenTheVerifiedFlagIsSetButNoNumberIsOnFile() {
        // Defence in depth: a verified flag with a null number (a data anomaly) must not text a code to null.
        when(user.isPhoneVerified()).thenReturn(true);
        when(user.getPhoneNumber()).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> handler.prepare(user, request)).isInstanceOf(ForbiddenException.class);
        verify(sms, never()).sendCode(any(), any(), any());
    }

    @Test
    void refusesToVerifyACodeOnceTheNumberIsNoLongerVerified() {
        // A code minted before the number changed must not still authenticate.
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(sms.generateCode()).thenReturn(CODE);
        handler.prepare(user, request);

        when(user.isPhoneVerified()).thenReturn(false);
        assertThat(handler.verify(user, code(CODE), request)).isFalse();
    }
}

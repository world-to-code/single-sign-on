package com.example.sso.auth.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.mfa.MfaService;
import com.example.sso.mfa.QrCodeService;
import com.example.sso.mfa.TotpEnrollment;
import com.example.sso.user.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link TotpFactorHandler}: enrollment (QR) on first use vs. a code challenge once
 * enrolled, and verification routed to enroll-confirm or steady-state TOTP check accordingly.
 */
@ExtendWith(MockitoExtension.class)
class TotpFactorHandlerTest {

    @Mock private MfaService mfa;
    @Mock private QrCodeService qrCodes;
    @Mock private UserAccount user;

    private final UUID userId = UUID.randomUUID();

    private TotpFactorHandler handler() {
        return new TotpFactorHandler(mfa, qrCodes);
    }

    private FactorVerificationRequest code(String value) {
        return new FactorVerificationRequest(value, null, null);
    }

    @Test
    void factorIsTotpAndEnrollableAtLogin() {
        assertThat(handler().factor()).isEqualTo(AuthFactor.TOTP);
        assertThat(handler().enrollableAtLogin()).isTrue();
    }

    @Test
    void isEnrolledReflectsAnEnabledTotp() {
        when(user.getId()).thenReturn(userId);
        when(mfa.hasEnabledTotp(userId)).thenReturn(true);

        assertThat(handler().isEnrolled(user)).isTrue();
    }

    @Test
    void prepareForAnEnrolledUserNeedsNoQrChallenge() {
        when(user.getId()).thenReturn(userId);
        when(mfa.hasEnabledTotp(userId)).thenReturn(true);

        FactorChallenge challenge = handler().prepare(user, new MockHttpServletRequest());

        assertThat(challenge.prepared()).isFalse();
        assertThat(challenge.qrDataUri()).isNull();
    }

    @Test
    void prepareForANewUserIssuesAnEnrollmentSecretAndQr() {
        when(user.getId()).thenReturn(userId);
        when(mfa.hasEnabledTotp(userId)).thenReturn(false);
        when(mfa.newEnrollment(user)).thenReturn(new TotpEnrollment("SECRET", "otpauth://totp/x"));
        when(qrCodes.pngDataUri("otpauth://totp/x")).thenReturn("data:image/png;base64,zzz");

        FactorChallenge challenge = handler().prepare(user, new MockHttpServletRequest());

        assertThat(challenge.prepared()).isTrue();
        assertThat(challenge.secret()).isEqualTo("SECRET");
        assertThat(challenge.qrDataUri()).isEqualTo("data:image/png;base64,zzz");
    }

    @Test
    void verifyForAnEnrolledUserChecksTheTotpCode() {
        when(user.getId()).thenReturn(userId);
        when(mfa.hasEnabledTotp(userId)).thenReturn(true);
        when(mfa.verifyTotp(userId, "123456")).thenReturn(true);

        assertThat(handler().verify(user, code("123456"), new MockHttpServletRequest())).isTrue();
    }

    @Test
    void verifyForANewUserConfirmsEnrollmentUsingThePendingSecret() {
        TotpFactorHandler handler = handler();
        when(user.getId()).thenReturn(userId);
        when(mfa.hasEnabledTotp(userId)).thenReturn(false);
        when(mfa.newEnrollment(user)).thenReturn(new TotpEnrollment("SECRET", "otpauth://totp/x"));
        lenient().when(qrCodes.pngDataUri("otpauth://totp/x")).thenReturn("data:png");
        MockHttpServletRequest request = new MockHttpServletRequest();
        handler.prepare(user, request); // seeds PENDING_SECRET

        when(mfa.confirmEnrollment(user, "SECRET", "123456")).thenReturn(true);

        assertThat(handler.verify(user, code("123456"), request)).isTrue();
    }

    @Test
    void verifyWithANullCodeReturnsFalse() {
        assertThat(handler().verify(user, code(null), new MockHttpServletRequest())).isFalse();
    }
}

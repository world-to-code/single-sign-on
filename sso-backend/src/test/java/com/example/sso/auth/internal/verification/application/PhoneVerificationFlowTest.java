package com.example.sso.auth.internal.verification.application;

import com.example.sso.auth.internal.login.application.CurrentUserProvider;
import com.example.sso.mfa.PhoneOwnershipProof;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Self-service phone enrollment for the SMS factor. Its job is to be the ONE place that flips
 * {@code phoneVerified}, and only against a redeemed proof: recording a number must not trust it, or the SMS
 * factor would be offered for a line the user never demonstrably controlled.
 */
@ExtendWith(MockitoExtension.class)
class PhoneVerificationFlowTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final String PHONE = "+14155550123";

    @Mock
    private CurrentUserProvider currentUser;
    @Mock
    private PhoneOwnershipProof proofs;
    @Mock
    private UserService users;
    @Mock
    private UserAccount user;

    private PhoneVerificationFlow flow() {
        lenient().when(currentUser.requireMfaComplete()).thenReturn(user);
        lenient().when(user.getId()).thenReturn(USER_ID);
        lenient().when(user.getOrgId()).thenReturn(ORG);
        lenient().when(user.getPhoneNumber()).thenReturn(PHONE);
        return new PhoneVerificationFlow(currentUser, proofs, users);
    }

    @Test
    void requestingRecordsTheNumberThenChallengesIt() {
        flow().request(PHONE);

        verify(users).enrollPhone(USER_ID, PHONE);
        verify(proofs).challenge(USER_ID, ORG, PHONE);
        verify(users, never()).markPhoneVerified(any(), any()); // requesting proves nothing
    }

    @Test
    void aRedeemedCodeMarksTheNumberVerified() {
        when(proofs.redeem(USER_ID, PHONE, "123456")).thenReturn(true);

        assertThatCode(() -> flow().confirm("123456")).doesNotThrowAnyException();

        verify(users).markPhoneVerified(USER_ID, PHONE);
    }

    @Test
    void aRejectedCodeLeavesTheNumberUnverified() {
        when(proofs.redeem(USER_ID, PHONE, "000000")).thenReturn(false);

        assertThatThrownBy(() -> flow().confirm("000000")).isInstanceOf(BadRequestException.class);
        verify(users, never()).markPhoneVerified(any(), any());
    }

    @Test
    void confirmingWithNoEnrolledNumberIsRejectedWithoutRedeeming() {
        PhoneVerificationFlow flow = flow();
        when(user.getPhoneNumber()).thenReturn(null);

        assertThatThrownBy(() -> flow.confirm("123456")).isInstanceOf(BadRequestException.class);
        verify(proofs, never()).redeem(any(), any(), any());
        verify(users, never()).markPhoneVerified(any(), any());
    }

    @Test
    void removingClearsTheNumber() {
        flow().remove();

        verify(users).removePhone(USER_ID);
    }
}

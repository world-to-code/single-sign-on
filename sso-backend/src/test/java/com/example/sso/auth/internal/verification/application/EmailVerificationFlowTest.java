package com.example.sso.auth.internal.verification.application;

import com.example.sso.auth.internal.login.application.CurrentUserProvider;

import com.example.sso.mfa.EmailOwnershipProof;
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
 * Re-proving your own email address. Its only job is to be the ONE place that flips {@code emailVerified},
 * and only against a redeemed proof: a flow that marked the address verified on request (rather than on
 * redemption) would hand the EMAIL factor back to whoever an admin last pointed the address at.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationFlowTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "alice@example.com";

    @Mock
    private CurrentUserProvider currentUser;
    @Mock
    private EmailOwnershipProof proofs;
    @Mock
    private UserService users;
    @Mock
    private UserAccount user;

    private EmailVerificationFlow flow() {
        lenient().when(currentUser.require()).thenReturn(user);
        lenient().when(user.getId()).thenReturn(USER_ID);
        lenient().when(user.getEmail()).thenReturn(EMAIL);
        return new EmailVerificationFlow(currentUser, proofs, users);
    }

    @Test
    void requestingChallengesTheUsersCurrentAddress() {
        when(user.isEmailVerified()).thenReturn(false);

        flow().request();

        verify(proofs).challenge(USER_ID, EMAIL);
        verify(users, never()).markEmailVerified(USER_ID); // requesting proves nothing
    }

    @Test
    void anAlreadyVerifiedAddressIsNotChallengedAgainAndSaysNothingAboutIt() {
        // An identify-first principal (pre-credential) can reach this endpoint, so a distinguishable response
        // would tell an attacker whether the victim's address is verified. Accept and do nothing.
        when(user.isEmailVerified()).thenReturn(true);

        assertThatCode(() -> flow().request()).doesNotThrowAnyException();
        verify(proofs, never()).challenge(any(), any());
    }

    @Test
    void aRedeemedCodeMarksTheAddressVerified() {
        when(proofs.redeem(USER_ID, EMAIL, "123456")).thenReturn(true);

        assertThatCode(() -> flow().confirm("123456")).doesNotThrowAnyException();

        verify(users).markEmailVerified(USER_ID);
    }

    @Test
    void aRejectedCodeLeavesTheAddressUnverified() {
        when(proofs.redeem(USER_ID, EMAIL, "000000")).thenReturn(false);

        assertThatThrownBy(() -> flow().confirm("000000")).isInstanceOf(BadRequestException.class);
        verify(users, never()).markEmailVerified(USER_ID);
    }
}

package com.example.sso.onboarding.internal.application;

import com.example.sso.onboarding.internal.domain.OnboardingInvitation;
import com.example.sso.onboarding.internal.domain.OnboardingInvitationRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OnboardingInvitationService}: {@code reissue} supersedes prior tokens for the user before
 * minting a fresh one and only ever applies to an inactive, password-less account; {@code redeem} activates the
 * account and, because following the mailed link proves the mailbox, marks its email verified.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingInvitationServiceTest {

    @Mock private OnboardingInvitationRepository invitations;
    @Mock private UserService users;
    @Mock private OneTimeTokens tokens;

    @InjectMocks private OnboardingInvitationService service;

    @Test
    void reissueDeletesPriorTokensThenIssuesAFreshOne() {
        UUID userId = UUID.randomUUID();
        UserAccount user = mock(UserAccount.class);
        when(user.isEnabled()).thenReturn(false);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(users.hasPassword(userId)).thenReturn(false);
        when(tokens.mint()).thenReturn("raw");
        when(tokens.hash("raw")).thenReturn("hash");

        String token = service.reissue(userId, Duration.ofHours(72));

        assertThat(token).isEqualTo("raw");
        InOrder order = inOrder(invitations);
        order.verify(invitations).deleteByUserId(userId);                 // supersede prior tokens FIRST
        order.verify(invitations).save(any(OnboardingInvitation.class));  // then persist the fresh one
    }

    @Test
    void reissueIsRejectedForAnAlreadyActivatedAccount() {
        UUID userId = UUID.randomUUID();
        UserAccount user = mock(UserAccount.class);
        when(user.isEnabled()).thenReturn(true); // already activated — must never be re-invited
        when(users.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.reissue(userId, Duration.ofHours(72)))
                .isInstanceOf(BadRequestException.class);
        verify(invitations, never()).save(any());
    }

    @Test
    void redeemActivatesTheAccountAndMarksItsEmailVerifiedBecauseTheInvitationLinkProvedTheMailbox() {
        UUID userId = UUID.randomUUID();
        OnboardingInvitation invitation = redeemableInvitationFor(userId);
        when(tokens.hash("raw")).thenReturn("hash");
        when(invitations.findByTokenHash("hash")).thenReturn(Optional.of(invitation));
        when(invitations.consume(any(), any())).thenReturn(1);

        service.redeem("raw", "password123");

        verify(users).setPassword(userId, "password123");
        verify(users).enable(userId);
        verify(users).markEmailVerified(userId);
    }

    @Test
    void redeemThatLosesTheConsumeRaceVerifiesNoEmail() {
        UUID userId = UUID.randomUUID();
        when(tokens.hash("raw")).thenReturn("hash");
        when(invitations.findByTokenHash("hash")).thenReturn(Optional.of(redeemableInvitationFor(userId)));
        when(invitations.consume(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.redeem("raw", "password123")).isInstanceOf(BadRequestException.class);

        verify(users, never()).markEmailVerified(any());
    }

    private OnboardingInvitation redeemableInvitationFor(UUID userId) {
        OnboardingInvitation invitation =
                new OnboardingInvitation(userId, "hash", Instant.now().plus(Duration.ofHours(1)));
        ReflectionTestUtils.setField(invitation, "id", UUID.randomUUID());
        return invitation;
    }
}

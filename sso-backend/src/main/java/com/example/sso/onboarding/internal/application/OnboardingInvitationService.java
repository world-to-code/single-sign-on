package com.example.sso.onboarding.internal.application;

import com.example.sso.onboarding.internal.domain.OnboardingInvitation;
import com.example.sso.onboarding.internal.domain.OnboardingInvitationRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and redeems one-time set-password invitations for tenant onboarding. The raw token is high-entropy
 * ({@code SecureRandom}, 256-bit) and returned ONLY from {@link #issue} (for the emailed link); only its
 * SHA-256 hash is stored. Redemption is single-use and time-boxed: it sets the invited user's password and
 * activates the account. Module-private — the onboarding orchestration issues, the controller redeems.
 */
@Service
@RequiredArgsConstructor
public class OnboardingInvitationService {

    private final OnboardingInvitationRepository invitations;
    private final UserService users;
    private final OneTimeTokens tokens;

    @Value("${sso.onboarding.min-password-length:8}")
    private int minPasswordLength;

    /**
     * Issues a one-time invitation for the user and returns the RAW token — only its hash is persisted. Only
     * a fresh onboarding account may be invited: it must be DISABLED and have NO password, so redemption
     * (which sets a password and enables) can only ever ACTIVATE a new admin — never re-enable a suspended
     * user nor reset an active one's password (a zero-trust guard against future mis-wiring of the caller).
     */
    @Transactional
    public String issue(UUID userId, Duration ttl) {
        UserAccount user = users.findById(userId).orElseThrow(() -> new NotFoundException("user not found"));
        if (user.isEnabled() || users.hasPassword(userId)) {
            throw new BadRequestException("an invitation can only be issued for an inactive, password-less account");
        }
        String token = tokens.mint();
        invitations.save(new OnboardingInvitation(userId, tokens.hash(token), Instant.now().plus(ttl)));
        return token;
    }

    /**
     * Redeems an invitation: sets the user's password and enables the account, then consumes the token.
     * An invalid/expired/used token is a non-revealing 400. A too-short password is rejected WITHOUT
     * consuming the token, so the invitee can retry with a stronger one.
     */
    @Transactional
    public void redeem(String rawToken, String newPassword) {
        OnboardingInvitation invitation = invitations.findByTokenHash(tokens.hash(rawToken))
                .filter(existing -> existing.isRedeemable(Instant.now()))
                .orElseThrow(() -> new BadRequestException("invalid or expired invitation"));
        if (newPassword == null || newPassword.length() < minPasswordLength) {
            throw new BadRequestException("password must be at least " + minPasswordLength + " characters");
        }
        // Consume FIRST, atomically: only the winner of a concurrent double-redeem gets 1 row (single-use).
        // A weak password was already rejected above WITHOUT consuming, so a compliant retry still works.
        if (invitations.consume(invitation.getId(), Instant.now()) == 0) {
            throw new BadRequestException("invalid or expired invitation");
        }
        users.setPassword(invitation.getUserId(), newPassword);
        users.enable(invitation.getUserId());
    }
}

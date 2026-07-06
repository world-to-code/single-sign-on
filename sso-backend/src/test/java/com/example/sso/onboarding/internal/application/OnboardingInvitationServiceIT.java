package com.example.sso.onboarding.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.NewUser;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The set-password invitation lifecycle end-to-end (real DB + real UserService): issuing a token, redeeming
 * it to set a password and activate the account, and the security properties — single-use, time-boxed, and a
 * too-short password that does NOT consume the token so the invitee can retry.
 */
class OnboardingInvitationServiceIT extends AbstractIntegrationTest {

    @Autowired
    OnboardingInvitationService invitations;
    @Autowired
    UserService users;

    private UUID userId;
    private String username;

    @BeforeEach
    void seed() {
        username = "onboard-" + UUID.randomUUID().toString().substring(0, 8);
        // An onboarding-provisioned admin starts WITH NO password and DISABLED — the invitation activates it.
        UserAccount created = users.createUser(new NewUser(username, username + "@example.com", username,
                null, Set.of("ROLE_USER")));
        userId = created.getId();
        users.disable(userId);
    }

    @AfterEach
    void cleanup() {
        users.delete(userId); // onboarding_invitation cascades off the user FK
    }

    @Test
    void redeemSetsThePasswordActivatesTheAccountAndIsSingleUse() {
        String token = invitations.issue(userId, Duration.ofHours(72));
        assertThat(users.hasPassword(userId)).isFalse();

        invitations.redeem(token, "chosen-passphrase-1");

        assertThat(users.hasPassword(userId)).isTrue();
        assertThat(users.findById(userId).orElseThrow().isEnabled()).isTrue();
        assertThat(users.verifyPassword(username, "chosen-passphrase-1")).isTrue();
        // Single-use: the token is now consumed.
        assertThatThrownBy(() -> invitations.redeem(token, "chosen-passphrase-1"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anExpiredInvitationIsRejected() {
        String token = invitations.issue(userId, Duration.ofSeconds(-1)); // already past

        assertThatThrownBy(() -> invitations.redeem(token, "chosen-passphrase-1"))
                .isInstanceOf(BadRequestException.class);
        assertThat(users.hasPassword(userId)).isFalse();
    }

    @Test
    void anUnknownTokenIsRejected() {
        assertThatThrownBy(() -> invitations.redeem("not-a-real-token", "chosen-passphrase-1"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void issuingForAnAlreadyActiveUserIsRejected() {
        // Guard: an invitation may only ACTIVATE a fresh onboarding account (disabled + no password), so it can
        // never re-enable a suspended user nor reset an active admin's password via a re-issued token.
        String active = "active-" + UUID.randomUUID().toString().substring(0, 8);
        UUID activeId = users.createUser(new NewUser(active, active + "@example.com", active,
                "a-real-password-1", Set.of("ROLE_USER"))).getId();
        try {
            assertThatThrownBy(() -> invitations.issue(activeId, Duration.ofHours(72)))
                    .isInstanceOf(BadRequestException.class);
        } finally {
            users.delete(activeId);
        }
    }

    @Test
    void aTooShortPasswordIsRejectedWithoutConsumingTheToken() {
        String token = invitations.issue(userId, Duration.ofHours(72));

        assertThatThrownBy(() -> invitations.redeem(token, "short")) // below min length 8
                .isInstanceOf(BadRequestException.class);
        // The token was NOT consumed — a compliant retry succeeds.
        assertThatCode(() -> invitations.redeem(token, "chosen-passphrase-1")).doesNotThrowAnyException();
        assertThat(users.hasPassword(userId)).isTrue();
    }
}

package com.example.sso.onboarding.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A one-time invitation to set a password and activate an account, issued during tenant onboarding. Only
 * the token's SHA-256 hash is persisted (the raw token lives only in the emailed link); single-use and
 * time-boxed. Global (not org-scoped) — resolved by token hash; the org linkage is the user's membership.
 * Created via constructor; consumed race-safely by the repository's conditional {@code consume} update — no
 * setters.
 */
@Entity
@Table(name = "onboarding_invitation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class OnboardingInvitation extends AuditedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    public OnboardingInvitation(UUID userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /** Redeemable only while unused AND unexpired (a pre-check; consumption is race-safe in the repository). */
    public boolean isRedeemable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }
}

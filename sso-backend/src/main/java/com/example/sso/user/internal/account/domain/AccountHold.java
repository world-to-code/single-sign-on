package com.example.sso.user.internal.account.domain;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A reversible hold on one account: sessions ended, and a second factor required of every sign-in until
 * {@code expiresAt}.
 *
 * <p>There is at most one per account (a unique index, not a convention), so re-placing REPLACES rather than
 * stacking — otherwise two detections could leave a hold nobody can see the end of. {@code orgId} is the HELD
 * user's tenant, so the tenant can see and lift a hold a platform operator placed on their user.
 */
@Entity
@Table(name = "account_hold")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class AccountHold extends AuditedEntity implements OrgOwned {

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 200)
    private String reason;

    /** The administrator who placed it, or null when a response system did — then {@link #correlationId} names it. */
    @Column(name = "placed_by")
    private UUID placedBy;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public AccountHold(UUID userId, UUID orgId, String reason, Instant expiresAt, UUID placedBy,
            String correlationId) {
        this.userId = userId;
        this.orgId = orgId;
        this.reason = reason;
        this.expiresAt = expiresAt;
        this.placedBy = placedBy;
        this.correlationId = correlationId;
        this.placedAt = Instant.now();
    }

    /**
     * Re-places this hold on new grounds. Everything the placement decided is replaced, {@code placedAt}
     * included — the hold in force is the one just asked for, and reporting the first detection's time
     * against the second's expiry would describe a hold that never existed.
     */
    public void replaceWith(String reason, Instant expiresAt, UUID placedBy, String correlationId) {
        this.reason = reason;
        this.expiresAt = expiresAt;
        this.placedBy = placedBy;
        this.correlationId = correlationId;
        this.placedAt = Instant.now();
    }

    /** Whether this hold constrains sign-in at {@code moment}. At its expiry it is already over. */
    public boolean inEffectAt(Instant moment) {
        return moment.isBefore(expiresAt);
    }
}

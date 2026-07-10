package com.example.sso.crypto.internal.domain;

import com.example.sso.shared.domain.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A tier's JWKS retention setting: how many rotated-away (inactive) signing keys stay published in that
 * tier's JWKS so tokens signed before a rotation remain verifiable until they expire. One row per tenant
 * plus an optional GLOBAL default ({@code orgId} null) every tenant inherits until it saves its own
 * (copy-on-write); with no rows at all the application.yml default applies. The table is RLS-FREE — the
 * JWK source reads it on browser-less signing paths (back-channel logout) — so scoping happens in the
 * query by the acting tier.
 */
@Entity
@Table(name = "signing_key_retention")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class SigningKeyRetention extends AbstractEntity {

    /** The tenant this setting governs, or null for the global default. Fixed at creation. */
    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "retained_inactive_keys", nullable = false)
    private int retainedInactiveKeys;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public SigningKeyRetention(UUID orgId, int retainedInactiveKeys) {
        this.orgId = orgId;
        this.retainedInactiveKeys = retainedInactiveKeys;
    }

    /** Domain mutation (intent-revealing, not a JavaBean setter): replace the bound and re-stamp. */
    public void update(int retainedInactiveKeys) {
        this.retainedInactiveKeys = retainedInactiveKeys;
        this.updatedAt = Instant.now();
    }
}

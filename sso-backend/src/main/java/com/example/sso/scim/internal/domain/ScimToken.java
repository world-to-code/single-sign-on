package com.example.sso.scim.internal.domain;
import com.example.sso.shared.domain.AuditedEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A bearer token authorizing SCIM provisioning calls. Only the token's SHA-256 hash is
 * persisted. Created via constructor; immutable after creation — no setters.
 */
@Entity
@Table(name = "scim_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class ScimToken extends AuditedEntity {

    @Column(length = 200)
    private String description;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public ScimToken(String description, String tokenHash, Instant expiresAt) {
        this.description = description;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isActiveAt(Instant now) {
        return enabled && (expiresAt == null || now.isBefore(expiresAt));
    }
}

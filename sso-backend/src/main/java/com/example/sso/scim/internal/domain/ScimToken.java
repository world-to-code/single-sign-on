package com.example.sso.scim.internal.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A bearer token authorizing SCIM provisioning calls. Only the token's SHA-256 hash is
 * persisted. Created via constructor; revoked via {@link #disable()} — no setters.
 */
@Entity
@Table(name = "scim_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class ScimToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 200)
    private String description;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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

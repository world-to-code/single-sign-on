package com.example.sso.user.internal.role.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An explicit user↔role assignment (row of {@code app_user_role}). Replaces the former
 * {@code @ManyToMany} on {@code AppUser}: assignment and revocation are now visible repository
 * inserts/deletes in the service layer, never a hidden JPA-managed collection with cascade.
 *
 * <p>An assignment may be TIME-BOUNDED. A null expiry means permanent, which is what every grant used to be;
 * a set one means the role stops counting at that instant. The row is not what enforces this — reads that
 * decide authorization ask for grants held AT a moment, and a sweeper removes lapsed ones and ends the
 * sessions they authorized, because a privilege that expires only in the database is still in the session
 * that already has it.
 */
@Entity
@Table(name = "app_user_role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class UserRole {

    @EmbeddedId
    private UserRoleId id;

    /** Null means permanent. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    private UserRole(UUID userId, UUID roleId, Instant expiresAt) {
        this.id = new UserRoleId(userId, roleId);
        this.expiresAt = expiresAt;
    }

    /** Granted until somebody revokes it — the shape every assignment had before expiry existed. */
    public static UserRole permanent(UUID userId, UUID roleId) {
        return new UserRole(userId, roleId, null);
    }

    /** Granted until {@code expiresAt}, after which it stops counting and the sweeper takes it away. */
    public static UserRole until(UUID userId, UUID roleId, Instant expiresAt) {
        return new UserRole(userId, roleId, expiresAt);
    }

    public boolean hasLapsedBy(Instant moment) {
        return expiresAt != null && !expiresAt.isAfter(moment);
    }

    public UUID getUserId() {
        return id.userId();
    }

    public UUID getRoleId() {
        return id.roleId();
    }
}

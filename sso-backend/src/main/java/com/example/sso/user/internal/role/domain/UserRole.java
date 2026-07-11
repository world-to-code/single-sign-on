package com.example.sso.user.internal.role.domain;


import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An explicit user↔role assignment (row of {@code app_user_role}). Replaces the former
 * {@code @ManyToMany} on {@code AppUser}: assignment and revocation are now visible repository
 * inserts/deletes in the service layer, never a hidden JPA-managed collection with cascade.
 */
@Entity
@Table(name = "app_user_role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class UserRole {

    @EmbeddedId
    private UserRoleId id;

    public UserRole(UUID userId, UUID roleId) {
        this.id = new UserRoleId(userId, roleId);
    }

    public UUID getUserId() {
        return id.userId();
    }

    public UUID getRoleId() {
        return id.roleId();
    }
}

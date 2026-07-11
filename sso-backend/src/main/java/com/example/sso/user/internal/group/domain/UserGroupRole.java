package com.example.sso.user.internal.group.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An explicit group→role delegation (row of {@code group_role}): every member of the group inherits
 * the role (and its permissions). Replaces the former {@code @ManyToMany} on {@code UserGroup}.
 */
@Entity
@Table(name = "group_role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class UserGroupRole {

    @EmbeddedId
    private UserGroupRoleId id;

    public UserGroupRole(UUID groupId, UUID roleId) {
        this.id = new UserGroupRoleId(groupId, roleId);
    }

    public UUID getGroupId() {
        return id.groupId();
    }

    public UUID getRoleId() {
        return id.roleId();
    }
}

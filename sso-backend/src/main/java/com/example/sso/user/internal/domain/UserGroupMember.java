package com.example.sso.user.internal.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An explicit group membership (row of {@code user_group_member}). Replaces the former
 * {@code @ElementCollection Set<UUID>} on {@code UserGroup}: joining/leaving a group is now a visible
 * repository insert/delete in the service layer.
 */
@Entity
@Table(name = "user_group_member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class UserGroupMember {

    @EmbeddedId
    private UserGroupMemberId id;

    public UserGroupMember(UUID groupId, UUID userId) {
        this.id = new UserGroupMemberId(groupId, userId);
    }

    public UUID getGroupId() {
        return id.groupId();
    }

    public UUID getUserId() {
        return id.userId();
    }
}

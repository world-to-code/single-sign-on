package com.example.sso.user.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite identity of a {@link UserGroupMember} row ({@code user_group_member}). */
@Embeddable
public record UserGroupMemberId(
        @Column(name = "group_id", nullable = false) UUID groupId,
        @Column(name = "user_id", nullable = false) UUID userId) implements Serializable {
}

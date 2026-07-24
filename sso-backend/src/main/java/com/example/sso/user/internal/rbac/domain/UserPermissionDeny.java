package com.example.sso.user.internal.rbac.domain;

import com.example.sso.shared.domain.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A USER-level negative permission: subtract {@code pattern} (a permission name or a {@code <resource>:*}
 * wildcard) from what user {@code userId} would otherwise hold — the most specific deny tier. {@code orgId}
 * matches the user's own tier (NULL = platform), enforced by a composite FK so a tenant cannot author a deny
 * naming a user in another org.
 */
@Entity
@Table(name = "app_user_permission_deny")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class UserPermissionDeny extends AbstractEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(nullable = false, length = 64)
    private String pattern;

    /** Who authored the deny + their apex role at the time — read by the lift guard (a peer can't lift a peer's). */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "writer_apex_role_id")
    private UUID writerApexRoleId;

    private UserPermissionDeny(UUID userId, UUID orgId, String pattern, UUID createdBy, UUID writerApexRoleId) {
        this.userId = userId;
        this.orgId = orgId;
        this.pattern = pattern;
        this.createdBy = createdBy;
        this.writerApexRoleId = writerApexRoleId;
    }

    public static UserPermissionDeny of(UUID userId, UUID orgId, String pattern, UUID createdBy,
            UUID writerApexRoleId) {
        return new UserPermissionDeny(userId, orgId, pattern, createdBy, writerApexRoleId);
    }
}

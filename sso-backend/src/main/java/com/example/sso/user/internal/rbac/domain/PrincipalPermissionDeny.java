package com.example.sso.user.internal.rbac.domain;

import com.example.sso.shared.domain.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A GROUP- or ROLE-level negative permission (a "principal" deny): subtract {@code pattern} from what holders
 * of that group/role would otherwise hold — the middle deny tier (GROUP = ROLE in specificity). Keyed on the
 * subject's ID, never its name (a role name has global/org collisions), mirroring the grant ceiling.
 */
@Entity
@Table(name = "principal_permission_deny")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class PrincipalPermissionDeny extends AbstractEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 8)
    private DenySubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(nullable = false, length = 64)
    private String pattern;

    /** Author + their apex role at write time — read by the lift guard. */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "writer_apex_role_id")
    private UUID writerApexRoleId;

    private PrincipalPermissionDeny(DenySubjectType subjectType, UUID subjectId, UUID orgId, String pattern,
            UUID createdBy, UUID writerApexRoleId) {
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.orgId = orgId;
        this.pattern = pattern;
        this.createdBy = createdBy;
        this.writerApexRoleId = writerApexRoleId;
    }

    public static PrincipalPermissionDeny of(DenySubjectType subjectType, UUID subjectId, UUID orgId, String pattern,
            UUID createdBy, UUID writerApexRoleId) {
        return new PrincipalPermissionDeny(subjectType, subjectId, orgId, pattern, createdBy, writerApexRoleId);
    }
}

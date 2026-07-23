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
 * An ORG-level negative permission — the least specific deny tier — subtracting {@code pattern} from every
 * member of {@code orgId}. {@code orgId} NULL is the PLATFORM veto: an absolute deny no tenant grant overrides
 * (and, by the split RLS, one only the platform context may write or delete).
 */
@Entity
@Table(name = "org_permission_deny")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class OrgPermissionDeny extends AbstractEntity {

    @Column(name = "org_id")
    private UUID orgId;

    @Column(nullable = false, length = 64)
    private String pattern;

    private OrgPermissionDeny(UUID orgId, String pattern) {
        this.orgId = orgId;
        this.pattern = pattern;
    }

    public static OrgPermissionDeny of(UUID orgId, String pattern) {
        return new OrgPermissionDeny(orgId, pattern);
    }
}

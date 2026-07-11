package com.example.sso.portal.internal.catalog.domain;
import com.example.sso.shared.domain.AuditedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import com.example.sso.portal.application.AppType;
import com.example.sso.tenancy.OrgOwned;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Assigns an application (OIDC client / SAML SP) to a subject (a user or a role/group). */
@Entity
@Table(name = "app_assignment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppAssignment extends AuditedEntity implements OrgOwned {

    public enum SubjectType { USER, ROLE, GROUP }

    @Enumerated(EnumType.STRING)
    @Column(name = "app_type", nullable = false, length = 8)
    private AppType appType;

    @Column(name = "app_id", nullable = false, length = 255)
    private String appId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 8)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    /** Optional per-app auth policy requiring extra/step-up authentication for this assignment. */
    @Column(name = "required_policy_id")
    private UUID requiredPolicyId;

    /** Owning tenant, or {@code null} for a GLOBAL assignment (e.g. the seeded admin-console grant) that
     *  applies across tenants; RLS confines an org assignment to its tenant's users (see {@code V53}). */
    @Column(name = "org_id")
    private UUID orgId;

    public AppAssignment(AppType appType, String appId, SubjectType subjectType, UUID subjectId,
            UUID requiredPolicyId, UUID orgId) {
        this.appType = appType;
        this.appId = appId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.requiredPolicyId = requiredPolicyId;
        this.orgId = orgId;
    }

    /** GLOBAL assignment (no owning tenant) — used by the admin-console seeder. */
    public AppAssignment(AppType appType, String appId, SubjectType subjectType, UUID subjectId, UUID requiredPolicyId) {
        this(appType, appId, subjectType, subjectId, requiredPolicyId, null);
    }
}

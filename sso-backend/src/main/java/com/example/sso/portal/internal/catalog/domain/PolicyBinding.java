package com.example.sso.portal.internal.catalog.domain;

import com.example.sso.portal.application.AppType;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Binds an application (OIDC client / SAML SP / portal) and a subject scope to an authentication policy
 * and/or a session policy. A {@code null} {@link #subjectType} means the binding applies to every subject
 * of the app; at least one of {@link #authPolicyId}/{@link #sessionPolicyId} is set (DB CHECK).
 */
@Entity
@Table(name = "policy_binding")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PolicyBinding extends AuditedEntity implements OrgOwned {

    public enum SubjectType { USER, GROUP, ROLE }

    @Enumerated(EnumType.STRING)
    @Column(name = "app_type", nullable = false, length = 8)
    private AppType appType;

    @Column(name = "app_id", nullable = false, length = 255)
    private String appId;

    /** {@code null} = the binding applies to every subject of the app (app-wide default). */
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", length = 8)
    private SubjectType subjectType;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "auth_policy_id")
    private UUID authPolicyId;

    @Column(name = "session_policy_id")
    private UUID sessionPolicyId;

    /** Tie-break among bindings of the same specificity tier; higher wins. */
    @Column(name = "priority", nullable = false)
    private int priority;

    /** Owning tenant, or {@code null} for a GLOBAL binding that applies across tenants. */
    @Column(name = "org_id")
    private UUID orgId;

    @Builder
    public PolicyBinding(AppType appType, String appId, SubjectType subjectType, UUID subjectId,
            UUID authPolicyId, UUID sessionPolicyId, int priority, UUID orgId) {
        this.appType = appType;
        this.appId = appId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.authPolicyId = authPolicyId;
        this.sessionPolicyId = sessionPolicyId;
        this.priority = priority;
        this.orgId = orgId;
    }
}

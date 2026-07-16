package com.example.sso.portal.internal.catalog.domain;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
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

    public enum SubjectType { USER, GROUP, ROLE, ATTRIBUTE }

    @Enumerated(EnumType.STRING)
    @Column(name = "app_type", nullable = false, length = 8)
    private AppType appType;

    @Column(name = "app_id", nullable = false, length = 255)
    private String appId;

    /** {@code null} = the binding applies to every subject of the app (app-wide default). */
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", length = 16)
    private SubjectType subjectType;

    /** The id subject, set for USER/GROUP/ROLE; {@code null} for all-subjects and ATTRIBUTE bindings. */
    @Column(name = "subject_id")
    private UUID subjectId;

    /** The metadata predicate key, set exactly for an {@link SubjectType#ATTRIBUTE} binding (else {@code null}). */
    @Column(name = "subject_attr_key", length = 64)
    private String subjectAttrKey;

    /** The metadata predicate value, set for a value-operator {@link SubjectType#ATTRIBUTE} binding (else null). */
    @Column(name = "subject_attr_value", length = 255)
    private String subjectAttrValue;

    /** How the predicate tests the key, set exactly for an {@link SubjectType#ATTRIBUTE} binding (else null). */
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_attr_op", length = 16)
    private AttributeOperator subjectAttrOp;

    @Column(name = "auth_policy_id")
    private UUID authPolicyId;

    @Column(name = "session_policy_id")
    private UUID sessionPolicyId;

    /** Same-specificity tie-break for the AUTH field; higher wins. Independent of {@link #sessionPriority}
     *  because a co-located row's auth and session policies (assigned separately) carry their own weights. */
    @Column(name = "priority", nullable = false)
    private int priority;

    /** Same-specificity tie-break for the SESSION field; higher wins (mirror of {@link #priority} for auth). */
    @Column(name = "session_priority", nullable = false)
    private int sessionPriority;

    /** Owning tenant, or {@code null} for a GLOBAL binding that applies across tenants. */
    @Column(name = "org_id")
    private UUID orgId;

    // Private, name-based builder: the ONLY assembly path, reached solely through the named factories below so
    // business logic never touches raw field order (adding/reordering a column stays contained to this class).
    @Builder(access = AccessLevel.PRIVATE)
    private PolicyBinding(AppType appType, String appId, SubjectType subjectType, UUID subjectId,
            String subjectAttrKey, String subjectAttrValue, AttributeOperator subjectAttrOp, UUID authPolicyId,
            UUID sessionPolicyId, int priority, int sessionPriority, UUID orgId) {
        this.appType = appType;
        this.appId = appId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.subjectAttrKey = subjectAttrKey;
        this.subjectAttrValue = subjectAttrValue;
        this.subjectAttrOp = subjectAttrOp;
        this.authPolicyId = authPolicyId;
        this.sessionPolicyId = sessionPolicyId;
        this.priority = priority;
        this.sessionPriority = sessionPriority;
        this.orgId = orgId;
    }

    /** A policy-less binding targeting every subject of the app in the given tier (the caller assigns an axis policy). */
    public static PolicyBinding forAllSubjects(AppType appType, String appId, UUID org) {
        return builder().appType(appType).appId(appId).orgId(org).build();
    }

    /** A policy-less binding targeting one id subject (USER/GROUP/ROLE) in the given tier. */
    public static PolicyBinding forSubject(AppType appType, String appId, SubjectType subjectType, UUID subjectId,
            UUID org) {
        return builder().appType(appType).appId(appId).subjectType(subjectType).subjectId(subjectId).orgId(org).build();
    }

    /** A policy-less binding targeting the users a metadata predicate ({@code key <op> value}) matches, in the tier. */
    public static PolicyBinding forAttribute(AppType appType, String appId, String attrKey, AttributeOperator op,
            String attrValue, UUID org) {
        return builder().appType(appType).appId(appId).subjectType(SubjectType.ATTRIBUTE)
                .subjectAttrKey(attrKey).subjectAttrOp(op).subjectAttrValue(attrValue).orgId(org).build();
    }

    /** The metadata predicate this ATTRIBUTE binding targets — its key/operator/value as one value object.
     *  The columns that compose a predicate are owned here, so their assembly lives here too (not in each reader). */
    public AttributePredicate subjectPredicate() {
        return new AttributePredicate(subjectAttrKey, subjectAttrOp, subjectAttrValue);
    }

    /** Point this binding at a different session policy (intent-revealing mutation, not a JavaBean setter). */
    public void assignSessionPolicy(UUID sessionPolicyId) {
        this.sessionPolicyId = sessionPolicyId;
    }

    /** Point this binding at a different auth (sign-on) policy (intent-revealing mutation, not a setter). */
    public void assignAuthPolicy(UUID authPolicyId) {
        this.authPolicyId = authPolicyId;
    }

    /** Reset the AUTH tie-break weight (used when the auth field is re-pointed at another policy). */
    public void reprioritize(int priority) {
        this.priority = priority;
    }

    /** Reset the SESSION tie-break weight (used when the session field is re-pointed at another policy). */
    public void reprioritizeSession(int sessionPriority) {
        this.sessionPriority = sessionPriority;
    }

    /** Whether this binding no longer carries any policy — the caller then deletes it (DB CHECK needs ≥1). */
    public boolean carriesNoPolicy() {
        return authPolicyId == null && sessionPolicyId == null;
    }
}

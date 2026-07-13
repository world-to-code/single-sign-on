package com.example.sso.authpolicy.internal.domain;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.example.sso.authpolicy.policy.AuthPolicyView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * An authentication policy: an ordered chain of {@link AuthPolicyStep}s. When several policies apply to a
 * user, the one with the highest {@code priority} wins. No setters — mutated via intention-revealing methods.
 *
 * <p>Which users/roles a policy governs (its login scope) lives in the {@code policy_binding} matrix, not on
 * the entity. The steps are a plain read association — NO cascade or orphan removal; the admin service
 * inserts/deletes them explicitly (via their own repositories), so every write is visible in the service.
 */
@Entity
@Table(name = "auth_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class AuthPolicy extends AuditedEntity implements AuthPolicyView, OrgOwned {

    // Tier-aware uniqueness (partial indexes in V46): global name, or (org_id, name) per tenant.
    @Column(nullable = false, length = 100)
    private String name;

    // NULL = a GLOBAL/default policy (resolved for every tenant, e.g. "Default"); non-null = a custom policy
    // owned by that organization (RLS-isolated, resolved only for that org's logins). Fixed at creation.
    @Column(name = "org_id")
    private UUID orgId;

    /** Higher value wins when multiple policies apply to a user. */
    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Sign-on/step-up posture: login participation, enroll-at-login, and the step-up freshness window. */
    @Embedded
    private SignOnRules signOnRules = SignOnRules.defaults();

    @OneToMany(mappedBy = "policy", fetch = FetchType.LAZY)
    @OrderBy("stepOrder ASC")
    private List<AuthPolicyStep> steps = new ArrayList<>();

    /** A global/default policy (no owning org). */
    public AuthPolicy(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    /** A policy owned by {@code orgId} (null = global). The org is fixed at creation. */
    public AuthPolicy(String name, int priority, UUID orgId) {
        this(name, priority);
        this.orgId = orgId;
    }

    public void updatePriority(int priority) {
        this.priority = priority;
    }

    /** Whether users governed by this policy may enroll a missing factor during login. */
    public void allowEnrollmentAtLogin(boolean allowEnrollmentAtLogin) {
        this.signOnRules = signOnRules.allowingEnrollmentAtLogin(allowEnrollmentAtLogin);
    }

    /** The per-app step-up re-authentication window, in minutes. */
    public void updateStepUpFreshnessMinutes(int minutes) {
        this.signOnRules = signOnRules.withStepUpFreshnessMinutes(minutes);
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    // Read-only views; the sign-on posture is delegated to the embedded value object.

    public boolean isAllowEnrollmentAtLogin() {
        return signOnRules.allowEnrollmentAtLogin();
    }

    public int getStepUpFreshnessMinutes() {
        return signOnRules.stepUpFreshnessMinutes();
    }

    // The step rows are persisted explicitly (override Lombok's @Getter).

    public List<AuthPolicyStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }
}

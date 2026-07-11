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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * An authentication policy: an ordered chain of {@link AuthPolicyStep}s assigned to users
 * and/or roles (groups). When several policies apply to a user, the one with the highest
 * {@code priority} wins. No setters — mutated via intention-revealing methods.
 *
 * <p>The steps and assignment rows are plain read associations — NO cascade or orphan removal. The
 * admin service inserts/deletes them explicitly (via their own repositories), so every write is
 * visible in the service code. These collections are read-only projections here.
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

    @OneToMany(mappedBy = "policy", fetch = FetchType.LAZY)
    private Set<AuthPolicyUser> userAssignments = new HashSet<>();

    @OneToMany(mappedBy = "policy", fetch = FetchType.LAZY)
    private Set<AuthPolicyRole> roleAssignments = new HashSet<>();

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

    /** Whether this policy governs login (true) or is reserved for per-app step-up only (false). */
    public void useForLogin(boolean appliesToLogin) {
        this.signOnRules = signOnRules.forLogin(appliesToLogin);
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

    public boolean isAppliesToLogin() {
        return signOnRules.appliesToLogin();
    }

    public boolean isAllowEnrollmentAtLogin() {
        return signOnRules.allowEnrollmentAtLogin();
    }

    public int getStepUpFreshnessMinutes() {
        return signOnRules.stepUpFreshnessMinutes();
    }

    // The assignment/step rows are persisted explicitly (override Lombok's @Getter).

    public List<AuthPolicyStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public Set<UUID> getAssignedUserIds() {
        return userAssignments.stream().map(AuthPolicyUser::getUserId).collect(Collectors.toUnmodifiableSet());
    }

    public Set<UUID> getAssignedRoleIds() {
        return roleAssignments.stream().map(AuthPolicyRole::getRoleId).collect(Collectors.toUnmodifiableSet());
    }
}

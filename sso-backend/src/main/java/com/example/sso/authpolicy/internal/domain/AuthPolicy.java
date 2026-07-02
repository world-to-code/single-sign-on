package com.example.sso.authpolicy.internal.domain;
import com.example.sso.shared.domain.AuditedEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyStepView;
import com.example.sso.authpolicy.AuthPolicyView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * An authentication policy: an ordered chain of {@link AuthPolicyStep}s assigned to users
 * and/or roles (groups). When several policies apply to a user, the one with the highest
 * {@code priority} wins. No setters — mutated via intention-revealing methods.
 */
@Entity
@Table(name = "auth_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class AuthPolicy extends AuditedEntity implements AuthPolicyView {

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** Higher value wins when multiple policies apply to a user. */
    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean enabled = true;

    /** True = a login (sign-on) policy that participates in login resolution (global if unassigned).
     *  False = an app-only policy, used only for per-app step-up via app_assignment.required_policy_id. */
    @Column(name = "applies_to_login", nullable = false)
    private boolean appliesToLogin = true;

    /** True = a user governed by this policy may set up a missing required factor (TOTP/passkey)
     *  during login (enroll-at-login). False = the factor must be pre-provisioned by an admin. */
    @Column(name = "allow_enrollment_at_login", nullable = false)
    private boolean allowEnrollmentAtLogin = true;

    /** App step-up re-auth window (minutes): a deliberate per-app step-up stays valid this long. */
    @Column(name = "step_up_freshness_minutes", nullable = false)
    private int stepUpFreshnessMinutes = 15;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("stepOrder ASC")
    private List<AuthPolicyStep> steps = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "auth_policy_user", joinColumns = @JoinColumn(name = "policy_id"))
    @Column(name = "user_id")
    private Set<UUID> assignedUserIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "auth_policy_role", joinColumns = @JoinColumn(name = "policy_id"))
    @Column(name = "role_id")
    private Set<UUID> assignedRoleIds = new HashSet<>();

    public AuthPolicy(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    public void addStep(AuthPolicyStep step) {
        step.assignTo(this);
        this.steps.add(step);
    }

    public void replaceSteps(List<AuthPolicyStep> newSteps) {
        this.steps.clear();
        newSteps.forEach(this::addStep);
    }

    public void updatePriority(int priority) {
        this.priority = priority;
    }

    /** Whether this policy governs login (true) or is reserved for per-app step-up only (false). */
    public void useForLogin(boolean appliesToLogin) {
        this.appliesToLogin = appliesToLogin;
    }

    /** Whether users governed by this policy may enroll a missing factor during login. */
    public void allowEnrollmentAtLogin(boolean allowEnrollmentAtLogin) {
        this.allowEnrollmentAtLogin = allowEnrollmentAtLogin;
    }

    /** The per-app step-up re-authentication window, in minutes. */
    public void updateStepUpFreshnessMinutes(int minutes) {
        this.stepUpFreshnessMinutes = minutes;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public void assignUsers(Collection<UUID> userIds) {
        this.assignedUserIds.clear();
        this.assignedUserIds.addAll(userIds);
    }

    public void assignRoles(Collection<UUID> roleIds) {
        this.assignedRoleIds.clear();
        this.assignedRoleIds.addAll(roleIds);
    }

    // Read-only views (override Lombok's @Getter); mutate via the behavior methods above.

    public List<AuthPolicyStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public Set<UUID> getAssignedUserIds() {
        return Collections.unmodifiableSet(assignedUserIds);
    }

    public Set<UUID> getAssignedRoleIds() {
        return Collections.unmodifiableSet(assignedRoleIds);
    }
}

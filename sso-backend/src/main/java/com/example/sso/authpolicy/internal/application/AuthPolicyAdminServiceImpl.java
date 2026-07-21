package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AppPolicyBindings;
import com.example.sso.authpolicy.policy.AuthPolicyAdminService;
import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.authpolicy.policy.AuthPolicySpec;
import com.example.sso.authpolicy.policy.AuthPolicyUpdate;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.authpolicy.policy.LoginAuthBindings;
import com.example.sso.authpolicy.internal.domain.AuthPolicy;
import com.example.sso.authpolicy.internal.domain.AuthPolicyRepository;
import com.example.sso.authpolicy.internal.domain.AuthPolicyStep;
import com.example.sso.authpolicy.internal.domain.AuthPolicyStepFactor;
import com.example.sso.authpolicy.internal.domain.AuthPolicyStepFactorRepository;
import com.example.sso.authpolicy.internal.domain.AuthPolicyStepRepository;
import com.example.sso.metadata.AttributePredicateGroup;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.account.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Default {@link AuthPolicyAdminService}. Admin CRUD plus seeding/self-healing of the non-editable
 * Default fallback policy. Every policy is owned by the acting tier: a tenant admin (org context bound)
 * creates/edits only their org's policies; the platform super-admin (no org bound) manages global policies
 * and, after drilling into an org, that org's policies. RLS enforces the same boundary at the row level.
 *
 * <p>Steps and their factor rows are managed EXPLICITLY through their own repositories — there is no JPA
 * cascade or orphan removal. Every insert/delete a mutation performs is therefore visible here: replacing a
 * policy's steps deletes the old factor rows, then the old step rows, then inserts the new ones. A policy's
 * login scope (which users/roles it governs) lives in the {@code policy_binding} matrix, written through
 * {@link LoginAuthBindings}; deleting a policy clears those bindings before the policy itself (FK RESTRICT).
 */
@Service
@RequiredArgsConstructor
public class AuthPolicyAdminServiceImpl implements AuthPolicyAdminService {
    private final AuthPolicyRepository repository;
    private final OrgTierGuard tierGuard;
    private final OrgContext orgContext;
    private final AuthPolicyStepRepository stepRepository;
    private final AuthPolicyStepFactorRepository stepFactorRepository;
    private final LoginAuthBindings loginBindings;
    private final AppPolicyBindings appBindings;
    private final UserService users;
    private final RoleService roles;

    @Override
    @Transactional
    public void seedDefault() {
        // The Default fallback is a GLOBAL policy (org_id IS NULL) — resolved for every tenant's login.
        AuthPolicy policy = repository.findByNameAndOrgIdIsNull(AuthPolicyResolver.DEFAULT_NAME)
                .orElseGet(() -> new AuthPolicy(AuthPolicyResolver.DEFAULT_NAME, 0));

        policy.enable();
        policy.allowEnrollmentAtLogin(true); // the fallback must let users bootstrap a required factor
        AuthPolicy saved = repository.save(policy);
        replaceSteps(saved, List.of(Set.of(AuthFactor.PASSWORD), Set.of(AuthFactor.TOTP)));
        loginBindings.replaceForPolicy(saved.getId(), saved.getPriority(), true, Set.of(), Set.of()); // every user's login
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provisionDefault(UUID orgId) {
        // REQUIRES_NEW: this runs from an AFTER_COMMIT listener (the org-created transaction is already
        // completing), so it must open its OWN physical transaction — a plain REQUIRES would find no active
        // transaction and the flush below would fail.
        // Bind the org for the whole read+write so RLS scopes the existence check AND the insert's WITH CHECK
        // to this tenant; saveAndFlush forces the INSERT while the GUC is still orgId (a deferred flush would
        // run after the scope restores the outer context and fail — see rls-connection-context-binder).
        orgContext.runInOrg(orgId, () -> {
            if (repository.findByNameAndOrgId(AuthPolicyResolver.DEFAULT_NAME, orgId).isPresent()) {
                return; // idempotent: the tenant already has its baseline
            }
            // Org-owned "Default" login policy: priority above the global 0 so it wins for this org, no
            // assignments so it applies to every member, canonical password-then-TOTP steps, and used for
            // login with enrollment allowed (so a member can bootstrap their required factor). Editable by
            // the tenant admin — it is NOT the immutable GLOBAL Default (org_id is non-null).
            AuthPolicy policy = new AuthPolicy(AuthPolicyResolver.DEFAULT_NAME, TENANT_DEFAULT_PRIORITY, orgId);
            policy.allowEnrollmentAtLogin(true);
            AuthPolicy saved = repository.saveAndFlush(policy);
            replaceSteps(saved, List.of(Set.of(AuthFactor.PASSWORD), Set.of(AuthFactor.TOTP)));
            loginBindings.replaceForPolicy(saved.getId(), saved.getPriority(), true, Set.of(), Set.of()); // every member's login
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuthPolicyView> listAll() {
        // Tier-scoped for the admin directory: a tenant admin (tier = their org) sees ONLY their org's policies,
        // NOT the GLOBAL default RLS keeps visible (and which they cannot edit — update/delete requireInTier
        // 404s it). The platform admin (tier null) sees the globals.
        UUID tier = tierGuard.currentTier();
        return repository.findAllByOrderByPriorityDesc().stream()
                .filter(policy -> Objects.equals(policy.getOrgId(), tier))
                .map(AuthPolicyView.class::cast).toList();
    }

    @Override
    @Transactional
    public AuthPolicyView create(AuthPolicySpec spec) {
        UUID creationOrg = tierGuard.currentTier();
        if (existsInTier(spec.name(), creationOrg)) {
            throw ConflictException.of("authpolicy.name.duplicate");
        }
        requirePriorityAvailable(spec.priority(), creationOrg, null);

        AuthPolicy policy = new AuthPolicy(spec.name(), spec.priority(), creationOrg);
        if (!spec.enabled()) {
            policy.disable();
        }
        policy.allowEnrollmentAtLogin(spec.allowEnrollmentAtLogin());
        policy.updateStepUpFreshnessMinutes(spec.stepUpFreshnessMinutes());

        AuthPolicy saved = repository.save(policy);
        replaceSteps(saved, spec.steps());
        applyLoginScope(saved, spec.appliesToLogin(), spec.userIds(), spec.roleIds(), spec.attributePredicates());

        return AuthPolicyProjection.of(saved, spec.steps());
    }

    @Override
    @Transactional
    public AuthPolicyView update(UUID id, AuthPolicyUpdate update) {
        AuthPolicy policy = tierGuard.requireInTier(repository.findById(id), () -> NotFoundException.of("policy.notFound"));
        if (isGlobalDefault(policy)) {
            throw BadRequestException.of("authpolicy.defaultNoEdit");
        }
        requirePriorityAvailable(update.priority(), policy.getOrgId(), policy.getId());

        policy.updatePriority(update.priority());
        if (update.enabled()) {
            policy.enable();
        } else {
            policy.disable();
        }
        policy.allowEnrollmentAtLogin(update.allowEnrollmentAtLogin());
        policy.updateStepUpFreshnessMinutes(update.stepUpFreshnessMinutes());

        replaceSteps(policy, update.steps());
        applyLoginScope(policy, update.appliesToLogin(), update.userIds(), update.roleIds(),
                update.attributePredicates());

        AuthPolicy saved = repository.save(policy);
        return AuthPolicyProjection.of(saved, update.steps());
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        AuthPolicy policy = tierGuard.requireInTier(repository.findById(id), () -> NotFoundException.of("policy.notFound"));
        if (isGlobalDefault(policy)) {
            throw BadRequestException.of("authpolicy.defaultNoDelete");
        }
        // An app assignment is NOT silently cleared (that would weaken the app's sign-on); block with a 409 so the
        // admin unassigns it first, instead of letting the auth_policy_id FK RESTRICT surface as a 500.
        if (appBindings.isAssignedToApp(id)) {
            throw ConflictException.of("authpolicy.assignedToApp");
        }

        deleteSteps(id);                    // each step's factor rows, then the step rows
        loginBindings.clearForPolicy(id);   // login bindings next — policy_binding.auth_policy_id is FK RESTRICT
        repository.delete(policy);          // finally the policy itself (no reliance on DB cascade)
    }

    // The immutable fallback is only the GLOBAL Default (org_id null); a tenant may legitimately own an
    // org-scoped policy that happens to be named "Default", and must still be able to edit/delete it.
    private boolean isGlobalDefault(AuthPolicy policy) {
        return policy.getOrgId() == null && AuthPolicyResolver.DEFAULT_NAME.equals(policy.getName());
    }

    // Duplicate-name check within the acting tier (RLS makes the global name globally unique, and each org's
    // names unique within that org — see the partial indexes).
    private boolean existsInTier(String name, UUID org) {
        return (org == null
                ? repository.findByNameAndOrgIdIsNull(name)
                : repository.findByNameAndOrgId(name, org)).isPresent();
    }

    /**
     * Priority is UNIQUE within a tier (each tenant's own set + the global set): login resolution breaks a
     * same-specificity tie on priority, so two competing policies must never share one. Reject a create/update
     * that would collide with another policy ({@code selfId} is the policy being updated, excluded; null on create).
     */
    private void requirePriorityAvailable(int priority, UUID org, UUID selfId) {
        List<AuthPolicy> atPriority = org == null
                ? repository.findByPriorityAndOrgIdIsNull(priority)
                : repository.findByPriorityAndOrgId(priority, org);
        if (atPriority.stream().anyMatch(p -> !p.getId().equals(selfId))) {
            throw ConflictException.of("authpolicy.priority.duplicate");
        }
    }

    /** Replaces a policy's steps: validate, delete the old step/factor rows, then insert the new ones. */
    private void replaceSteps(AuthPolicy policy, List<? extends Set<AuthFactor>> steps) {
        for (Set<AuthFactor> factors : steps) {
            if (factors.isEmpty()) {
                throw BadRequestException.of("authpolicy.step.factorRequired");
            }
        }

        deleteSteps(policy.getId());

        int order = 1;
        for (Set<AuthFactor> factors : steps) {
            AuthPolicyStep step = stepRepository.save(new AuthPolicyStep(policy, order++));
            for (AuthFactor factor : factors) {
                stepFactorRepository.save(new AuthPolicyStepFactor(step, factor));
            }
        }
    }

    /** Deletes every step of a policy — its factor rows first, then the step rows (visible, no cascade). */
    private void deleteSteps(UUID policyId) {
        List<AuthPolicyStep> existing = stepRepository.findByPolicyId(policyId);
        for (AuthPolicyStep step : existing) {
            stepFactorRepository.deleteByStepId(step.getId());
        }
        stepRepository.deleteAll(existing);
        stepRepository.flush(); // ensure the old rows are gone before re-inserting
    }

    /**
     * Write the policy's login scope into the {@code policy_binding} matrix: validate that every targeted
     * subject is assignable (same-org or global), then hand the whole scope to {@link LoginAuthBindings},
     * which reconciles the all-subjects/per-subject bindings. Assignability is checked only for a scope that
     * is actually bound (a non-login policy binds nothing, so its stray ids reference no principal).
     */
    private void applyLoginScope(AuthPolicy policy, boolean appliesToLogin, Set<UUID> requestedUsers,
                                 Set<UUID> requestedRoles, Set<AttributePredicateGroup> requestedPredicates) {
        Set<UUID> userIds = requestedUsers == null ? Set.of() : requestedUsers;
        Set<UUID> roleIds = requestedRoles == null ? Set.of() : requestedRoles;
        // A predicate is stamped and resolved in the acting tier (RLS), so it only ever matches the tenant's
        // own users — there is no cross-org subject to validate, unlike a user/role id.
        Set<AttributePredicateGroup> predicates = requestedPredicates == null ? Set.of() : requestedPredicates;
        predicates.forEach(this::requireTargetable);
        if (appliesToLogin) {
            for (UUID userId : userIds) {
                requireAssignable(policy, users.orgIdOf(userId), "user");
            }
            for (UUID roleId : roleIds) {
                requireAssignable(policy, roles.orgIdOf(roleId), "role");
            }
        }
        loginBindings.replaceForPolicy(policy.getId(), policy.getPriority(), appliesToLogin, userIds, roleIds,
                predicates);
    }

    /** Defence in depth beyond the request DTO's {@code @AssertTrue}: a policy target may only use an operator on
     *  the targetable allow-list (guarding a future operator not yet admitted), else a spec built outside the API
     *  would 500 on the child-table CHECK instead of a clean 400. */
    private void requireTargetable(AttributePredicateGroup group) {
        group.firstNonTargetableOperator().ifPresent(operator -> {
            throw BadRequestException.of("policy.operator.cannotTarget", operator);
        });
    }

    /**
     * A policy may target a subject that is GLOBAL (org null — e.g. the shared ROLE_USER) or belongs to the
     * policy's OWN org; never another tenant's user or role. This stops a tenant admin from binding a policy to
     * a foreign-tenant principal — a reference RLS would leave inert at resolution but which should not exist.
     */
    private void requireAssignable(AuthPolicy policy, Optional<UUID> subjectOrg, String kind) {
        UUID org = subjectOrg.orElse(null);
        // Org-agnostic message: don't confirm that the rejected id belongs to ANOTHER org (a foreign-tenant
        // existence hint), only that it is not assignable here.
        if (org != null && !Objects.equals(org, policy.getOrgId())) {
            throw BadRequestException.of("authpolicy.assignment.notAllowed", kind);
        }
    }
}

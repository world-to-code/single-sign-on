package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyAdminService;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.AuthPolicySpec;
import com.example.sso.authpolicy.AuthPolicyUpdate;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.authpolicy.internal.domain.AuthPolicy;
import com.example.sso.authpolicy.internal.domain.AuthPolicyRepository;
import com.example.sso.authpolicy.internal.domain.AuthPolicyStep;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgTierGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Default {@link AuthPolicyAdminService}. Admin CRUD plus seeding/self-healing of the non-editable
 * Default fallback policy. Every policy is owned by the acting tier: a tenant admin (org context bound)
 * creates/edits only their org's policies; the platform super-admin (no org bound) manages global policies
 * and, after drilling into an org, that org's policies. RLS enforces the same boundary at the row level.
 */
@Service
@RequiredArgsConstructor
public class AuthPolicyAdminServiceImpl implements AuthPolicyAdminService {
    private final AuthPolicyRepository repository;
    private final OrgTierGuard tierGuard;

    @Override
    @Transactional
    public void seedDefault() {
        // The Default fallback is a GLOBAL policy (org_id IS NULL) — resolved for every tenant's login.
        AuthPolicy policy = repository.findByNameAndOrgIdIsNull(AuthPolicyResolver.DEFAULT_NAME)
                .orElseGet(() -> new AuthPolicy(AuthPolicyResolver.DEFAULT_NAME, 0));

        policy.enable();
        policy.useForLogin(true);
        policy.allowEnrollmentAtLogin(true); // the fallback must let users bootstrap a required factor
        policy.replaceSteps(List.of(
                new AuthPolicyStep(1, Set.of(AuthFactor.PASSWORD)),
                new AuthPolicyStep(2, Set.of(AuthFactor.TOTP))));

        repository.save(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuthPolicyView> listAll() {
        return repository.findAllByOrderByPriorityDesc().stream()
                .map(AuthPolicyView.class::cast).toList();
    }

    @Override
    @Transactional
    public AuthPolicyView create(AuthPolicySpec spec) {
        UUID creationOrg = tierGuard.currentTier();
        if (existsInTier(spec.name(), creationOrg)) {
            throw new ConflictException("policy name already exists");
        }

        AuthPolicy policy = new AuthPolicy(spec.name(), spec.priority(), creationOrg);
        if (!spec.enabled()) {
            policy.disable();
        }
        policy.useForLogin(spec.appliesToLogin());
        policy.allowEnrollmentAtLogin(spec.allowEnrollmentAtLogin());
        policy.updateStepUpFreshnessMinutes(spec.stepUpFreshnessMinutes());
        applySteps(policy, spec.steps());
        policy.assignUsers(spec.userIds() == null ? Set.of() : spec.userIds());
        policy.assignRoles(spec.roleIds() == null ? Set.of() : spec.roleIds());

        return repository.save(policy);
    }

    @Override
    @Transactional
    public AuthPolicyView update(UUID id, AuthPolicyUpdate update) {
        AuthPolicy policy = tierGuard.requireInTier(repository.findById(id), () -> new NotFoundException("policy not found"));
        if (isGlobalDefault(policy)) {
            throw new BadRequestException("the Default fallback policy cannot be edited");
        }

        policy.updatePriority(update.priority());
        if (update.enabled()) {
            policy.enable();
        } else {
            policy.disable();
        }
        policy.useForLogin(update.appliesToLogin());
        policy.allowEnrollmentAtLogin(update.allowEnrollmentAtLogin());
        policy.updateStepUpFreshnessMinutes(update.stepUpFreshnessMinutes());
        applySteps(policy, update.steps());
        policy.assignUsers(update.userIds() == null ? Set.of() : update.userIds());
        policy.assignRoles(update.roleIds() == null ? Set.of() : update.roleIds());

        return repository.save(policy);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        AuthPolicy policy = tierGuard.requireInTier(repository.findById(id), () -> new NotFoundException("policy not found"));
        if (isGlobalDefault(policy)) {
            throw new BadRequestException("the Default policy cannot be deleted");
        }

        repository.delete(policy);
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

    private void applySteps(AuthPolicy policy, List<? extends Set<AuthFactor>> steps) {
        List<AuthPolicyStep> built = new ArrayList<>();
        int order = 1;
        for (Set<AuthFactor> factors : steps) {
            if (factors.isEmpty()) {
                throw new BadRequestException("a policy step needs at least one factor");
            }
            built.add(new AuthPolicyStep(order++, new HashSet<>(factors)));
        }

        policy.replaceSteps(built);
    }
}

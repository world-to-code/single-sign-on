package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyAdminService;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.authpolicy.internal.domain.AuthPolicy;
import com.example.sso.authpolicy.internal.domain.AuthPolicyRepository;
import com.example.sso.authpolicy.internal.domain.AuthPolicyStep;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
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
 * Default fallback policy.
 */
@Service
@RequiredArgsConstructor
public class AuthPolicyAdminServiceImpl implements AuthPolicyAdminService {
    private final AuthPolicyRepository repository;

    @Override
    @Transactional
    public void seedDefault() {
        AuthPolicy policy = repository.findByName(AuthPolicyResolver.DEFAULT_NAME)
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
    public AuthPolicyView create(String name, int priority, boolean enabled, boolean appliesToLogin,
                             boolean allowEnrollmentAtLogin, List<? extends Set<AuthFactor>> steps,
                             Set<UUID> userIds, Set<UUID> roleIds, int stepUpFreshnessMinutes) {
        if (repository.findByName(name).isPresent()) {
            throw new ConflictException("policy name already exists");
        }
        AuthPolicy policy = new AuthPolicy(name, priority);
        if (!enabled) {
            policy.disable();
        }
        policy.useForLogin(appliesToLogin);
        policy.allowEnrollmentAtLogin(allowEnrollmentAtLogin);
        policy.updateStepUpFreshnessMinutes(stepUpFreshnessMinutes);
        applySteps(policy, steps);
        policy.assignUsers(userIds == null ? Set.of() : userIds);
        policy.assignRoles(roleIds == null ? Set.of() : roleIds);
        return repository.save(policy);
    }

    @Override
    @Transactional
    public AuthPolicyView update(UUID id, int priority, boolean enabled, boolean appliesToLogin,
                             boolean allowEnrollmentAtLogin, List<? extends Set<AuthFactor>> steps,
                             Set<UUID> userIds, Set<UUID> roleIds, int stepUpFreshnessMinutes) {
        AuthPolicy policy = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("policy not found"));
        if (AuthPolicyResolver.DEFAULT_NAME.equals(policy.getName())) {
            throw new BadRequestException("the Default fallback policy cannot be edited");
        }
        policy.updatePriority(priority);
        if (enabled) {
            policy.enable();
        } else {
            policy.disable();
        }
        policy.useForLogin(appliesToLogin);
        policy.allowEnrollmentAtLogin(allowEnrollmentAtLogin);
        policy.updateStepUpFreshnessMinutes(stepUpFreshnessMinutes);
        applySteps(policy, steps);
        policy.assignUsers(userIds == null ? Set.of() : userIds);
        policy.assignRoles(roleIds == null ? Set.of() : roleIds);
        return repository.save(policy);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        AuthPolicy policy = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("policy not found"));
        if (AuthPolicyResolver.DEFAULT_NAME.equals(policy.getName())) {
            throw new BadRequestException("the Default policy cannot be deleted");
        }
        repository.delete(policy);
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

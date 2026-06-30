package com.example.sso.authpolicy;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Write path of the authentication-policy engine: admin CRUD plus seeding/self-healing of the
 * non-editable Default fallback policy.
 */
@Service
public class AuthPolicyAdminService {

    private final AuthPolicyRepository repository;

    public AuthPolicyAdminService(AuthPolicyRepository repository) {
        this.repository = repository;
    }

    /**
     * Ensures the fallback policy exists and is canonical (password then TOTP). Runs on every
     * boot and self-heals the Default if it was ever left in a bad state — it is the guaranteed
     * sane fallback used when no assigned policy matches, and is not user-editable.
     */
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

    @Transactional(readOnly = true)
    public List<AuthPolicy> listAll() {
        return repository.findAllByOrderByPriorityDesc();
    }

    @Transactional
    public AuthPolicy create(String name, int priority, boolean enabled, boolean appliesToLogin,
                             boolean allowEnrollmentAtLogin, List<? extends Set<AuthFactor>> steps,
                             Set<UUID> userIds, Set<UUID> roleIds) {
        if (repository.findByName(name).isPresent()) {
            throw new ConflictException("policy name already exists");
        }
        AuthPolicy policy = new AuthPolicy(name, priority);
        if (!enabled) {
            policy.disable();
        }
        policy.useForLogin(appliesToLogin);
        policy.allowEnrollmentAtLogin(allowEnrollmentAtLogin);
        applySteps(policy, steps);
        policy.assignUsers(userIds == null ? Set.of() : userIds);
        policy.assignRoles(roleIds == null ? Set.of() : roleIds);
        return repository.save(policy);
    }

    @Transactional
    public AuthPolicy update(UUID id, int priority, boolean enabled, boolean appliesToLogin,
                             boolean allowEnrollmentAtLogin, List<? extends Set<AuthFactor>> steps,
                             Set<UUID> userIds, Set<UUID> roleIds) {
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
        applySteps(policy, steps);
        policy.assignUsers(userIds == null ? Set.of() : userIds);
        policy.assignRoles(roleIds == null ? Set.of() : roleIds);
        return repository.save(policy);
    }

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

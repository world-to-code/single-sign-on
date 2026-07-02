package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.authpolicy.internal.domain.AuthPolicy;
import com.example.sso.authpolicy.internal.domain.AuthPolicyRepository;
import com.example.sso.shared.IdName;
import com.example.sso.user.UserAccount;
import com.example.sso.user.RoleRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link AuthPolicyResolver}. Resolves the effective policy for a user (highest-priority
 * assigned or global policy, else the seeded default). Used by the login/MFA flow and per-app step-up.
 */
@Service
@RequiredArgsConstructor
public class AuthPolicyResolverImpl implements AuthPolicyResolver {

    private final AuthPolicyRepository repository;

    @Override
    @Transactional(readOnly = true)
    public AuthPolicyView resolveForUser(UserAccount user) {
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());

        // Candidates = policies targeted at this user / their roles, PLUS "global" policies (no user and
        // no role assignment) which apply to everyone — the seeded Default is the lowest-priority global.
        // Highest priority wins (admin controls precedence; Default is priority 0).
        List<AuthPolicy> candidates = new ArrayList<>(repository.findEnabledAssignedToUser(user.getId()));
        if (!roleIds.isEmpty()) {
            candidates.addAll(repository.findEnabledAssignedToAnyRole(roleIds));
        }
        candidates.addAll(repository.findEnabledGlobal());

        return candidates.stream()
                .max(Comparator.comparingInt(AuthPolicy::getPriority))
                .<AuthPolicyView>map(p -> p)
                .orElseGet(this::defaultPolicy);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthPolicyView defaultPolicy() {
        return repository.findByNameFetchingSteps(DEFAULT_NAME)
                .orElseThrow(() -> new IllegalStateException("Default auth policy is missing"));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthPolicyView> highestPriorityEnabled(Collection<UUID> policyIds) {
        return repository.findAllByIdFetchingSteps(policyIds).stream() // steps fetched (read detached)
                .filter(AuthPolicy::isEnabled)
                .max(Comparator.comparingInt(AuthPolicy::getPriority))
                .map(AuthPolicyView.class::cast);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID policyId) {
        return repository.existsById(policyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdName> policyNames() {
        return repository.findIdNames();
    }
}

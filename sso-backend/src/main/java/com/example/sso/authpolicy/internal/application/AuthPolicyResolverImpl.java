package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.authpolicy.internal.domain.AuthPolicy;
import com.example.sso.authpolicy.internal.domain.AuthPolicyRepository;
import com.example.sso.shared.IdName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link AuthPolicyResolver}. Resolves the seeded default policy and the highest-priority enabled
 * policy among a given set (per-app step-up). Login-scope resolution lives in the {@code policy_binding}
 * matrix, not here.
 */
@Service
@RequiredArgsConstructor
public class AuthPolicyResolverImpl implements AuthPolicyResolver {

    private final AuthPolicyRepository repository;

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

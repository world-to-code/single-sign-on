package com.example.sso.authpolicy;

import com.example.sso.user.AppUser;
import com.example.sso.user.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read path of the authentication-policy engine: resolves the effective policy for a user
 * (highest-priority assigned policy, else the seeded default). Used by the login/MFA flow.
 */
@Service
@RequiredArgsConstructor
public class AuthPolicyResolver {
    public static final String DEFAULT_NAME = "Default";

    private final AuthPolicyRepository repository;

    @Transactional(readOnly = true)
    public AuthPolicy resolveForUser(AppUser user) {
        Set<UUID> roleIds = user.getRoles().stream().map(Role::getId).collect(Collectors.toSet());
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
                .orElseGet(this::defaultPolicy);
    }

    @Transactional(readOnly = true)
    public AuthPolicy defaultPolicy() {
        return repository.findByName(DEFAULT_NAME)
                .orElseThrow(() -> new IllegalStateException("Default auth policy is missing"));
    }
}

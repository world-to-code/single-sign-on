package com.example.sso.session;

import com.example.sso.user.AppUser;
import com.example.sso.user.AppUserRepository;
import com.example.sso.user.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read path of the session-policy engine: resolves the effective session policy for a user
 * (highest-priority assigned/global policy, else the seeded Default). Mirrors
 * {@code AuthPolicyResolver}. The hot per-request path goes through the cached
 * {@link SessionPolicyService} instead; this DB-backed resolver is the canonical read path.
 */
@Service
public class SessionPolicyResolver {

    public static final String DEFAULT_NAME = "Default";

    private final SessionPolicyRepository repository;
    private final AppUserRepository users;

    public SessionPolicyResolver(SessionPolicyRepository repository, AppUserRepository users) {
        this.repository = repository;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public SessionPolicy resolveForUser(AppUser user) {
        Set<UUID> roleIds = user.getRoles().stream().map(Role::getId).collect(Collectors.toSet());
        // Candidates = policies targeted at this user / their roles, PLUS "global" policies (no user
        // and no role assignment) which apply to everyone — the seeded Default is the lowest-priority
        // global. Highest priority wins (admin controls precedence; Default is priority 0).
        List<SessionPolicy> candidates = new ArrayList<>(repository.findEnabledAssignedToUser(user.getId()));
        if (!roleIds.isEmpty()) {
            candidates.addAll(repository.findEnabledAssignedToAnyRole(roleIds));
        }
        candidates.addAll(repository.findEnabledGlobal());
        return candidates.stream()
                .max(Comparator.comparingInt(SessionPolicy::getPriority))
                .orElseGet(this::defaultPolicy);
    }

    /** Resolves the session policy for a username, falling back to Default if the user is unknown. */
    @Transactional(readOnly = true)
    public SessionPolicy resolveForUsername(String username) {
        return users.findByUsername(username)
                .map(this::resolveForUser)
                .orElseGet(this::defaultPolicy);
    }

    @Transactional(readOnly = true)
    public SessionPolicy defaultPolicy() {
        return repository.findByName(DEFAULT_NAME)
                .orElseThrow(() -> new IllegalStateException("Default session policy is missing"));
    }
}

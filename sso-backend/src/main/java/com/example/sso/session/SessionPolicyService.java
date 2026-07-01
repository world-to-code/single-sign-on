package com.example.sso.session;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.AppUser;
import com.example.sso.user.AppUserRepository;
import com.example.sso.user.Role;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The single session-policy bean injected by the security filters, interceptors and controllers.
 * Holds an in-memory cache of all policies (assignments are EAGER, so the cached detached entities
 * are safe to read off the request path without a database round-trip) and resolves the effective
 * policy per user from that cache. The cache is refreshed on every mutation. Also owns admin CRUD
 * and seeding/self-healing of the non-editable {@code Default} fallback.
 */
@Service
@RequiredArgsConstructor
public class SessionPolicyService {
    public static final String DEFAULT_NAME = SessionPolicyResolver.DEFAULT_NAME;

    private final SessionPolicyRepository repository;
    private final AppUserRepository users;
    private volatile List<SessionPolicy> cached = List.of();

    @PostConstruct
    @Transactional(readOnly = true)
    public void load() {
        this.cached = List.copyOf(repository.findAllByOrderByPriorityDesc());
    }

    private void refresh() {
        this.cached = List.copyOf(repository.findAllByOrderByPriorityDesc());
    }

    // --- Read path (served from the cache) ---

    /** The effective session policy for the user: highest-priority assigned/global, else Default. */
    public SessionPolicy resolveForUser(AppUser user) {
        Set<UUID> roleIds = user.getRoles().stream().map(Role::getId).collect(Collectors.toSet());
        return cached.stream()
                .filter(SessionPolicy::isEnabled)
                .filter(p -> appliesTo(p, user.getId(), roleIds))
                .max(Comparator.comparingInt(SessionPolicy::getPriority))
                .orElseGet(this::defaultPolicy);
    }

    /** Resolves by username for the filter/interceptor callers; Default if the user is unknown. */
    public SessionPolicy resolveForUsername(String username) {
        return users.findByUsername(username)
                .map(this::resolveForUser)
                .orElseGet(this::defaultPolicy);
    }

    /** The non-editable Default fallback (also supplies the GLOBAL session-cookie attributes). */
    public SessionPolicy defaultPolicy() {
        return cached.stream()
                .filter(p -> DEFAULT_NAME.equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Default session policy is missing"));
    }

    private static boolean appliesTo(SessionPolicy p, UUID userId, Set<UUID> roleIds) {
        boolean assignedToUser = p.getAssignedUserIds().contains(userId);
        boolean assignedToRole = p.getAssignedRoleIds().stream().anyMatch(roleIds::contains);
        boolean global = p.getAssignedUserIds().isEmpty() && p.getAssignedRoleIds().isEmpty();
        return assignedToUser || assignedToRole || global;
    }

    // --- Write path (admin CRUD + seeding) ---

    /**
     * Ensures the Default fallback exists. Runs on every boot; the Default is the guaranteed sane
     * fallback used when no assigned policy matches, and is not user-editable. Its settings are left
     * intact if it already exists (it carries the live, admin-tuned global defaults).
     */
    @Transactional
    public void seedDefault() {
        if (repository.findByName(DEFAULT_NAME).isEmpty()) {
            repository.save(new SessionPolicy(DEFAULT_NAME, 0));
        }
        refresh();
    }

    @Transactional(readOnly = true)
    public List<SessionPolicy> listAll() {
        return repository.findAllByOrderByPriorityDesc();
    }

    /**
     * Validates the comma-separated re-auth factor list: every token must name a real {@link AuthFactor}
     * (TOTP/FIDO2/PASSWORD/EMAIL) and the list may not be empty. This stops an admin saving garbage that
     * would leave step-up impossible (an effective lockout from sensitive operations).
     */
    private static String validateReauthFactors(String reauthFactors) {
        List<String> tokens = reauthFactors == null ? List.of()
                : Arrays.stream(reauthFactors.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (tokens.isEmpty()) {
            throw new BadRequestException("at least one re-auth factor is required");
        }
        Set<String> valid = EnumSet.allOf(AuthFactor.class).stream().map(Enum::name).collect(Collectors.toSet());
        for (String token : tokens) {
            if (!valid.contains(token)) {
                throw new BadRequestException("unknown re-auth factor: " + token);
            }
        }
        return String.join(",", tokens);
    }

    @Transactional
    public SessionPolicy create(String name, int priority, boolean enabled, int absoluteTimeoutMinutes,
                                int idleTimeoutMinutes, int reauthIntervalMinutes, String reauthFactors,
                                boolean bindClient, int maxConcurrentSessions, boolean rotateOnReauth,
                                String cookieSameSite,
                                Set<UUID> userIds, Set<UUID> roleIds) {
        if (repository.findByName(name).isPresent()) {
            throw new ConflictException("policy name already exists");
        }
        reauthFactors = validateReauthFactors(reauthFactors);
        SessionPolicy policy = new SessionPolicy(name, priority);
        if (!enabled) {
            policy.disable();
        }
        policy.update(absoluteTimeoutMinutes, idleTimeoutMinutes, reauthIntervalMinutes, reauthFactors,
                bindClient, maxConcurrentSessions, rotateOnReauth, cookieSameSite);
        policy.assignUsers(userIds == null ? Set.of() : userIds);
        policy.assignRoles(roleIds == null ? Set.of() : roleIds);
        SessionPolicy saved = repository.save(policy);
        refresh();
        return saved;
    }

    @Transactional
    public SessionPolicy update(UUID id, int priority, boolean enabled, int absoluteTimeoutMinutes,
                                int idleTimeoutMinutes, int reauthIntervalMinutes, String reauthFactors,
                                boolean bindClient, int maxConcurrentSessions, boolean rotateOnReauth,
                                String cookieSameSite,
                                Set<UUID> userIds, Set<UUID> roleIds) {
        SessionPolicy policy = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("policy not found"));
        reauthFactors = validateReauthFactors(reauthFactors);
        boolean isDefault = DEFAULT_NAME.equals(policy.getName());
        if (isDefault) {
            // The Default is not assignable/reprioritisable, but it DOES carry the global cookie
            // settings + the baseline timeouts, so allow editing those (priority/assignment fixed).
            policy.update(absoluteTimeoutMinutes, idleTimeoutMinutes, reauthIntervalMinutes, reauthFactors,
                    bindClient, maxConcurrentSessions, rotateOnReauth, cookieSameSite);
        } else {
            policy.updatePriority(priority);
            if (enabled) {
                policy.enable();
            } else {
                policy.disable();
            }
            policy.update(absoluteTimeoutMinutes, idleTimeoutMinutes, reauthIntervalMinutes, reauthFactors,
                    bindClient, maxConcurrentSessions, rotateOnReauth, cookieSameSite);
            policy.assignUsers(userIds == null ? Set.of() : userIds);
            policy.assignRoles(roleIds == null ? Set.of() : roleIds);
        }
        SessionPolicy saved = repository.save(policy);
        refresh();
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        SessionPolicy policy = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("policy not found"));
        if (DEFAULT_NAME.equals(policy.getName())) {
            throw new BadRequestException("the Default policy cannot be deleted");
        }
        repository.delete(policy);
        refresh();
    }
}

package com.example.sso.session.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.session.internal.domain.SessionPolicy;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.session.SessionPolicySpec;
import com.example.sso.session.SessionPolicyUpdate;
import com.example.sso.session.internal.domain.SessionPolicyRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import com.example.sso.user.RoleRef;
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
 * Default {@link SessionPolicyService}. Holds an in-memory cache of all policies (assignments are
 * EAGER, so the cached detached entities are safe to read off the request path without a database
 * round-trip) and resolves the effective policy per user from that cache. The cache is refreshed on
 * every mutation. Also owns admin CRUD and seeding/self-healing of the non-editable Default fallback.
 */
@Service
@RequiredArgsConstructor
public class SessionPolicyServiceImpl implements SessionPolicyService {

    private final SessionPolicyRepository repository;
    private final UserService users;
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

    @Override
    public SessionPolicyDetails resolveForUser(UserAccount user) {
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());

        return cached.stream()
                .filter(SessionPolicy::isEnabled)
                .filter(p -> appliesTo(p, user.getId(), roleIds))
                .max(Comparator.comparingInt(SessionPolicy::getPriority))
                .<SessionPolicyDetails>map(p -> p)
                .orElseGet(this::defaultPolicy);
    }

    @Override
    public SessionPolicyDetails resolveForUsername(String username) {
        return users.findByUsername(username)
                .map(this::resolveForUser)
                .orElseGet(this::defaultPolicy);
    }

    @Override
    public SessionPolicyDetails defaultPolicy() {
        return cached.stream()
                .filter(p -> DEFAULT_NAME.equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Default session policy is missing"));
    }

    private boolean appliesTo(SessionPolicy p, UUID userId, Set<UUID> roleIds) {
        boolean assignedToUser = p.getAssignedUserIds().contains(userId);
        boolean assignedToRole = p.getAssignedRoleIds().stream().anyMatch(roleIds::contains);
        boolean global = p.getAssignedUserIds().isEmpty() && p.getAssignedRoleIds().isEmpty();

        return assignedToUser || assignedToRole || global;
    }

    // --- Write path (admin CRUD + seeding) ---

    @Override
    @Transactional
    public void seedDefault() {
        if (repository.findByName(DEFAULT_NAME).isEmpty()) {
            repository.save(new SessionPolicy(DEFAULT_NAME, 0));
        }

        refresh();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionPolicyDetails> listAll() {
        return repository.findAllByOrderByPriorityDesc().stream()
                .map(SessionPolicyDetails.class::cast).toList();
    }

    /**
     * Validates the comma-separated re-auth factor list: every token must name a real {@link AuthFactor}
     * (TOTP/FIDO2/PASSWORD/EMAIL) and the list may not be empty. This stops an admin saving garbage that
     * would leave step-up impossible (an effective lockout from sensitive operations).
     */
    private String validateReauthFactors(String reauthFactors) {
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

    @Override
    @Transactional
    public SessionPolicyDetails create(SessionPolicySpec spec) {
        if (repository.findByName(spec.name()).isPresent()) {
            throw new ConflictException("policy name already exists");
        }

        String reauthFactors = validateReauthFactors(spec.reauthFactors());
        SessionPolicy policy = new SessionPolicy(spec.name(), spec.priority());
        if (!spec.enabled()) {
            policy.disable();
        }
        policy.update(spec.absoluteTimeoutMinutes(), spec.idleTimeoutMinutes(), spec.reauthIntervalMinutes(),
                reauthFactors, spec.bindClient(), spec.maxConcurrentSessions(), spec.rotateOnReauth(),
                spec.cookieSameSite());
        policy.assignUsers(spec.userIds() == null ? Set.of() : spec.userIds());
        policy.assignRoles(spec.roleIds() == null ? Set.of() : spec.roleIds());

        SessionPolicy saved = repository.save(policy);
        refresh();

        return saved;
    }

    @Override
    @Transactional
    public SessionPolicyDetails update(UUID id, SessionPolicyUpdate update) {
        SessionPolicy policy = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("policy not found"));

        String reauthFactors = validateReauthFactors(update.reauthFactors());
        boolean isDefault = DEFAULT_NAME.equals(policy.getName());
        if (isDefault) {
            // The Default is not assignable/reprioritisable, but it DOES carry the global cookie
            // settings + the baseline timeouts, so allow editing those (priority/assignment fixed).
            policy.update(update.absoluteTimeoutMinutes(), update.idleTimeoutMinutes(),
                    update.reauthIntervalMinutes(), reauthFactors, update.bindClient(),
                    update.maxConcurrentSessions(), update.rotateOnReauth(), update.cookieSameSite());
        } else {
            policy.updatePriority(update.priority());
            if (update.enabled()) {
                policy.enable();
            } else {
                policy.disable();
            }
            policy.update(update.absoluteTimeoutMinutes(), update.idleTimeoutMinutes(),
                    update.reauthIntervalMinutes(), reauthFactors, update.bindClient(),
                    update.maxConcurrentSessions(), update.rotateOnReauth(), update.cookieSameSite());
            policy.assignUsers(update.userIds() == null ? Set.of() : update.userIds());
            policy.assignRoles(update.roleIds() == null ? Set.of() : update.roleIds());
        }

        SessionPolicy saved = repository.save(policy);
        refresh();

        return saved;
    }

    @Override
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

package com.example.sso.session.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.session.IpRuleSpec;
import com.example.sso.session.NetworkZoneService;
import com.example.sso.session.internal.domain.IpAction;
import com.example.sso.session.internal.domain.IpRuleEntry;
import com.example.sso.session.internal.domain.SessionPolicy;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.session.SessionPolicySpec;
import com.example.sso.session.SessionPolicyUpdate;
import com.example.sso.session.internal.domain.SessionPolicyRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import com.example.sso.user.RoleRef;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link SessionPolicyService}. Holds an in-memory cache of all policies whose LAZY assignment
 * sets are fetch-joined at refresh time (before the entities detach), so the cached detached entities
 * are safe to read off the request path without a database round-trip. Resolves the effective policy
 * per user from that cache. The cache is refreshed on
 * every mutation. Also owns admin CRUD and seeding/self-healing of the non-editable Default fallback.
 */
@Service
@RequiredArgsConstructor
public class SessionPolicyServiceImpl implements SessionPolicyService {

    private final SessionPolicyRepository repository;
    private final UserService users;
    private final NetworkZoneService networkZones;
    private final OrgContext orgContext;
    private final OrgTierGuard tierGuard;
    private final ApplicationEventPublisher events;
    private volatile List<SessionPolicy> cached = List.of();

    @PostConstruct
    public void load() {
        reload();
    }

    /**
     * Rebuilds the cache in the PLATFORM context so it holds EVERY tenant's policies (fetch-joining each
     * policy's LAZY sets before it detaches). A tenant-scoped transaction only sees its own tier under RLS,
     * so the cache must be (re)loaded cross-org; resolution then filters per request org. The query runs in
     * its own connection (no ambient transaction) whose GUC is set from the platform context here.
     */
    private void reload() {
        this.cached = orgContext.callAsPlatform(
                () -> List.copyOf(repository.findAllWithAssignmentsByPriorityDesc()));
    }

    /** Rebuild AFTER the mutating transaction commits, so the reload reads the committed cross-org rows. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onCacheChanged(SessionPolicyCacheChanged event) {
        reload();
    }

    // --- Read path (served from the cache) ---

    @Override
    public SessionPolicyDetails resolveForUser(UserAccount user) {
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());
        UUID currentOrg = orgContext.currentOrg().orElse(null);

        return cached.stream()
                .filter(SessionPolicy::isEnabled)
                .filter(p -> inScope(p, currentOrg))
                .filter(p -> appliesTo(p, user.getId(), roleIds))
                .max(Comparator.comparingInt(SessionPolicy::getPriority))
                .<SessionPolicyDetails>map(p -> p)
                .orElseGet(this::defaultPolicy);
    }

    // A policy applies to a request only if it is GLOBAL (org_id null) or owned by the request's bound org.
    // With no org bound (e.g. an unauthenticated chain) only global policies apply — never another tenant's.
    private boolean inScope(SessionPolicy p, UUID currentOrg) {
        return p.getOrgId() == null || p.getOrgId().equals(currentOrg);
    }

    @Override
    public SessionPolicyDetails resolveForUsername(String username) {
        return users.findByUsername(username)
                .map(this::resolveForUser)
                .orElseGet(this::defaultPolicy);
    }

    @Override
    public SessionPolicyDetails defaultPolicy() {
        // The fallback is the GLOBAL Default (org_id null); a tenant may also own a policy named "Default".
        return cached.stream()
                .filter(p -> p.getOrgId() == null && DEFAULT_NAME.equals(p.getName()))
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
        if (repository.findByNameAndOrgIdIsNull(DEFAULT_NAME).isEmpty()) {
            repository.save(new SessionPolicy(DEFAULT_NAME, 0)); // the global fallback (org_id null)
        }

        events.publishEvent(new SessionPolicyCacheChanged());
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

    /** Resolves each rule's zone id (which must name an existing network zone) into the embeddable entries. */
    private Set<IpRuleEntry> toIpRules(List<IpRuleSpec> rules) {
        if (rules == null) {
            return Set.of();
        }
        Set<IpRuleEntry> entries = new LinkedHashSet<>();
        for (IpRuleSpec r : rules) {
            UUID zoneId;
            try {
                zoneId = UUID.fromString(r.zoneId());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("invalid zone id: " + r.zoneId());
            }
            if (!networkZones.exists(zoneId)) {
                throw new BadRequestException("unknown network zone: " + r.zoneId());
            }
            entries.add(new IpRuleEntry(zoneId, IpAction.valueOf(r.action()), r.priority()));
        }
        return entries;
    }

    @Override
    @Transactional
    public SessionPolicyDetails create(SessionPolicySpec spec) {
        UUID creationOrg = tierGuard.currentTier();
        if (existsInTier(spec.name(), creationOrg)) {
            throw new ConflictException("policy name already exists");
        }

        String reauthFactors = validateReauthFactors(spec.reauthFactors());
        String stepUpFactors = validateReauthFactors(spec.stepUpFactors());
        SessionPolicy policy = new SessionPolicy(spec.name(), spec.priority(), creationOrg);
        if (!spec.enabled()) {
            policy.disable();
        }
        policy.update(spec.absoluteTimeoutMinutes(), spec.idleTimeoutMinutes(), spec.reauthIntervalMinutes(),
                reauthFactors, spec.sensitiveReauthWindowMinutes(), stepUpFactors, spec.bindClient(),
                spec.maxConcurrentSessions(), spec.rotateOnReauth(), spec.cookieSameSite());
        policy.assignUsers(spec.userIds() == null ? Set.of() : spec.userIds());
        policy.assignRoles(spec.roleIds() == null ? Set.of() : spec.roleIds());
        policy.assignIpRules(toIpRules(spec.ipRules()));

        SessionPolicy saved = repository.save(policy);
        events.publishEvent(new SessionPolicyCacheChanged());

        return saved;
    }

    @Override
    @Transactional
    public SessionPolicyDetails update(UUID id, SessionPolicyUpdate update) {
        SessionPolicy policy = tierGuard.requireInTier(repository.findById(id), () -> new NotFoundException("policy not found"));

        String reauthFactors = validateReauthFactors(update.reauthFactors());
        String stepUpFactors = validateReauthFactors(update.stepUpFactors());
        boolean isDefault = isGlobalDefault(policy);
        if (isDefault) {
            // The Default is not assignable/reprioritisable, but it DOES carry the global cookie
            // settings + the baseline timeouts, so allow editing those (priority/assignment fixed).
            policy.update(update.absoluteTimeoutMinutes(), update.idleTimeoutMinutes(),
                    update.reauthIntervalMinutes(), reauthFactors, update.sensitiveReauthWindowMinutes(),
                    stepUpFactors, update.bindClient(),
                    update.maxConcurrentSessions(), update.rotateOnReauth(), update.cookieSameSite());
        } else {
            policy.updatePriority(update.priority());
            if (update.enabled()) {
                policy.enable();
            } else {
                policy.disable();
            }
            policy.update(update.absoluteTimeoutMinutes(), update.idleTimeoutMinutes(),
                    update.reauthIntervalMinutes(), reauthFactors, update.sensitiveReauthWindowMinutes(),
                    stepUpFactors, update.bindClient(),
                    update.maxConcurrentSessions(), update.rotateOnReauth(), update.cookieSameSite());
            policy.assignUsers(update.userIds() == null ? Set.of() : update.userIds());
            policy.assignRoles(update.roleIds() == null ? Set.of() : update.roleIds());
        }
        // IP rules are policy config (not an assignment) — the Default may carry them too (global restriction).
        policy.assignIpRules(toIpRules(update.ipRules()));

        SessionPolicy saved = repository.save(policy);
        events.publishEvent(new SessionPolicyCacheChanged());

        return saved;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        SessionPolicy policy = tierGuard.requireInTier(repository.findById(id), () -> new NotFoundException("policy not found"));
        if (isGlobalDefault(policy)) {
            throw new BadRequestException("the Default policy cannot be deleted");
        }

        repository.delete(policy);
        events.publishEvent(new SessionPolicyCacheChanged());
    }

    // Duplicate-name check within the acting tier (partial-unique indexes make the global name and each
    // org's names unique within their own tier).
    private boolean existsInTier(String name, UUID org) {
        return (org == null
                ? repository.findByNameAndOrgIdIsNull(name)
                : repository.findByNameAndOrgId(name, org)).isPresent();
    }

    // The immutable fallback is only the GLOBAL Default (org_id null); a tenant may own a policy named
    // "Default" and must still be able to edit/delete it.
    private boolean isGlobalDefault(SessionPolicy policy) {
        return policy.getOrgId() == null && DEFAULT_NAME.equals(policy.getName());
    }
}

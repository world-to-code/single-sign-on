package com.example.sso.session.internal.policy.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.metadata.AttributePredicateGroup;
import com.example.sso.session.networkzone.IpRuleSpec;
import com.example.sso.session.networkzone.NetworkZoneService;
import com.example.sso.session.policy.SessionBindings;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.policy.SessionPolicySpec;
import com.example.sso.session.policy.SessionPolicyUpdate;
import com.example.sso.session.internal.networkzone.domain.IpAction;
import com.example.sso.session.internal.networkzone.domain.IpRuleEntry;
import com.example.sso.session.internal.policy.domain.SessionPolicy;
import com.example.sso.session.internal.policy.domain.SessionRules;
import com.example.sso.session.internal.policy.domain.SessionPolicyIpRule;
import com.example.sso.session.internal.policy.domain.SessionPolicyIpRuleRepository;
import com.example.sso.session.internal.policy.domain.SessionPolicyRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.account.UserService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link SessionPolicyService}. Holds an in-memory cache of all policies, each composed with its
 * explicitly-loaded assignment sets and IP rules ({@link CachedSessionPolicy}), so resolution runs off the
 * cache without a database round-trip. The cache is refreshed on every mutation. Also owns admin CRUD and
 * seeding/self-healing of the non-editable Default fallback.
 *
 * <p>Which users/roles a policy governs lives in the {@code policy_binding} matrix, written through
 * {@link SessionBindings}. IP rules stay as explicit child rows ({@link SessionPolicyIpRule}) managed here
 * (whole-set replace computes the diff), so the code shows exactly which rows change.
 */
@Service
@RequiredArgsConstructor
public class SessionPolicyServiceImpl implements SessionPolicyService {

    private final SessionPolicyRepository repository;
    private final SessionPolicyIpRuleRepository policyIpRules;
    private final SessionBindings sessionBindings;
    private final UserService users;
    private final RoleService roles;
    private final NetworkZoneService networkZones;
    private final OrgContext orgContext;
    private final OrgTierGuard tierGuard;
    private final ApplicationEventPublisher events;
    private volatile List<CachedSessionPolicy> cached = List.of();

    @PostConstruct
    public void load() {
        reload();
    }

    /**
     * Rebuilds the cache in the PLATFORM context so it holds EVERY tenant's policies: {@link #loadAll} reads
     * {@code session_policy} (RLS-guarded), so a tenant-scoped transaction would only see its own tier. The
     * cache must be cross-org (resolution filters per request org afterwards via {@link #inScope}), so the
     * reload runs as platform. It runs in its own connection whose GUC is set from the platform context here.
     */
    private void reload() {
        this.cached = orgContext.callAsPlatform(this::loadAll);
    }

    /** Rebuild AFTER the mutating transaction commits, so the reload reads the committed cross-org rows. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onCacheChanged(SessionPolicyCacheChanged event) {
        reload();
    }

    /** Loads every policy with its IP rules in a fixed number of queries (no per-policy N+1). */
    private List<CachedSessionPolicy> loadAll() {
        Map<UUID, List<IpRuleEntry>> ipRulesByPolicy = policyIpRules.findAll().stream()
                .collect(Collectors.groupingBy(SessionPolicyIpRule::policyId,
                        Collectors.mapping(SessionPolicyIpRule::toEntry, Collectors.toList())));

        return repository.findAllByOrderByPriorityDesc().stream()
                .map(policy -> new CachedSessionPolicy(policy,
                        toSpecs(ipRulesByPolicy.getOrDefault(policy.getId(), List.of()))))
                .toList();
    }

    // --- Read path (served from the cache) ---

    @Override
    public Optional<SessionPolicyDetails> findById(UUID id) {
        return cached.stream()
                .filter(p -> p.getId().equals(id))
                .findFirst()
                .map(SessionPolicyDetails.class::cast);
    }

    @Override
    public SessionPolicyDetails defaultPolicy() {
        // The fallback is the GLOBAL Default (org_id null); a tenant may also own a policy named "Default".
        return cached.stream()
                .filter(p -> p.policy().getOrgId() == null && DEFAULT_NAME.equals(p.getName()))
                .findFirst()
                .<SessionPolicyDetails>map(p -> p)
                .orElseThrow(() -> new IllegalStateException("Default session policy is missing"));
    }

    @Override
    public SessionPolicyDetails resolveDefault() {
        // Prefer the bound org's own Default (the tenant's unconditional catch-all) over the global baseline,
        // so a member never steps down to the weaker global Default when no more-specific binding applies.
        UUID org = orgContext.currentOrg().orElse(null);
        if (org != null) {
            Optional<SessionPolicyDetails> orgDefault = cached.stream()
                    .filter(p -> org.equals(p.policy().getOrgId()) && DEFAULT_NAME.equals(p.getName()))
                    .<SessionPolicyDetails>map(p -> p)
                    .findFirst();
            if (orgDefault.isPresent()) {
                return orgDefault.get();
            }
        }
        return defaultPolicy();
    }

    // --- Write path (admin CRUD + seeding) ---

    @Override
    @Transactional
    public void seedDefault() {
        // The global fallback (org_id null), governing every user's session via its all-subjects binding.
        SessionPolicy policy = repository.findByNameAndOrgIdIsNull(DEFAULT_NAME)
                .orElseGet(() -> repository.save(new SessionPolicy(DEFAULT_NAME, 0)));
        sessionBindings.replaceForPolicy(policy.getId(), policy.getPriority(), Set.of(), Set.of());
        events.publishEvent(new SessionPolicyCacheChanged());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provisionDefault(UUID orgId) {
        // REQUIRES_NEW: this runs from an AFTER_COMMIT listener (the org-created transaction is already
        // completing), so it must open its OWN physical transaction — a plain REQUIRES would find no active
        // transaction and the flush below would fail.
        // Bind the org for the whole read+write so RLS scopes the existence check AND the insert's WITH CHECK
        // to this tenant; saveAndFlush forces the INSERT while the GUC is still orgId (a deferred flush would
        // run after the scope restores the outer context and fail — see rls-connection-context-binder).
        orgContext.runInOrg(orgId, () -> {
            if (repository.findByNameAndOrgId(DEFAULT_NAME, orgId).isPresent()) {
                return; // idempotent: the tenant already has its baseline
            }
            // Org-owned "Default": priority above the global 0 so it wins for this org, no user/role
            // assignments so it applies to every member, standard baseline knobs (SessionRules.defaults()).
            // Editable by the tenant admin — it is NOT the immutable GLOBAL Default (org_id is non-null).
            SessionPolicy saved = repository.saveAndFlush(new SessionPolicy(DEFAULT_NAME, TENANT_DEFAULT_PRIORITY, orgId));
            sessionBindings.replaceForPolicy(saved.getId(), saved.getPriority(), Set.of(), Set.of()); // every member
        });
        events.publishEvent(new SessionPolicyCacheChanged());
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionPolicyDetails> listAll() {
        // Tier-scoped for the admin directory (its only caller): a tenant admin (tier = their org) sees ONLY
        // their org's policies, NOT the GLOBAL default RLS keeps visible — which they cannot edit anyway
        // (update/delete tierGuard.requireInTier → 404). The platform admin (tier null) sees the globals.
        UUID tier = tierGuard.currentTier();
        return loadAll().stream()
                .filter(cached -> Objects.equals(cached.policy().getOrgId(), tier))
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
            throw BadRequestException.of("session.policy.reauthFactorRequired");
        }

        Set<String> valid = EnumSet.allOf(AuthFactor.class).stream().map(Enum::name).collect(Collectors.toSet());
        for (String token : tokens) {
            if (!valid.contains(token)) {
                throw BadRequestException.of("session.policy.reauthFactorUnknown", token);
            }
        }

        return String.join(",", tokens);
    }

    /** Resolves each rule's zone id (which must name an existing network zone) into validated value objects. */
    private List<IpRuleEntry> toIpRules(List<IpRuleSpec> rules) {
        if (rules == null) {
            return List.of();
        }
        Set<IpRuleEntry> entries = new LinkedHashSet<>();
        for (IpRuleSpec r : rules) {
            UUID zoneId;
            try {
                zoneId = UUID.fromString(r.zoneId());
            } catch (IllegalArgumentException e) {
                throw BadRequestException.of("session.policy.invalidZoneId", r.zoneId());
            }
            if (!networkZones.exists(zoneId)) {
                throw BadRequestException.of("session.policy.unknownZone", r.zoneId());
            }
            entries.add(new IpRuleEntry(zoneId, IpAction.valueOf(r.action()), r.priority()));
        }
        return List.copyOf(entries);
    }

    private List<IpRuleSpec> toSpecs(Collection<IpRuleEntry> entries) {
        return entries.stream()
                .sorted(Comparator.comparingInt(IpRuleEntry::priority))
                .map(e -> new IpRuleSpec(e.zoneId().toString(), e.action().name(), e.priority()))
                .toList();
    }

    @Override
    @Transactional
    public SessionPolicyDetails create(SessionPolicySpec spec) {
        UUID creationOrg = tierGuard.currentTier();
        if (existsInTier(spec.name(), creationOrg)) {
            throw ConflictException.of("session.policy.duplicate");
        }
        requirePriorityAvailable(spec.priority(), creationOrg, null);

        String reauthFactors = validateReauthFactors(spec.reauthFactors());
        String stepUpFactors = validateReauthFactors(spec.stepUpFactors());
        Set<UUID> userIds = spec.userIds() == null ? Set.of() : Set.copyOf(spec.userIds());
        Set<UUID> roleIds = spec.roleIds() == null ? Set.of() : Set.copyOf(spec.roleIds());
        Set<AttributePredicateGroup> predicates = predicatesOf(spec.attributePredicates());
        List<IpRuleEntry> ipRules = toIpRules(spec.ipRules()); // validates zone references before any write
        String cookieSameSite = effectiveCookieSameSite(spec.cookieSameSite(),
                creationOrg == null && DEFAULT_NAME.equals(spec.name()));

        SessionPolicy policy = new SessionPolicy(spec.name(), spec.priority(), creationOrg);
        if (!spec.enabled()) {
            policy.disable();
        }
        policy.update(new SessionRules(spec.absoluteTimeoutMinutes(), spec.idleTimeoutMinutes(),
                spec.reauthIntervalMinutes(), reauthFactors, spec.sensitiveReauthWindowMinutes(), stepUpFactors,
                spec.bindClient(), spec.maxConcurrentSessions(), spec.rotateOnReauth(), cookieSameSite));
        SessionPolicy saved = repository.save(policy);

        applyAssignmentScope(saved, userIds, roleIds, predicates);
        replaceIpRules(saved.getId(), ipRules);
        events.publishEvent(new SessionPolicyCacheChanged());

        return new CachedSessionPolicy(saved, toSpecs(ipRules));
    }

    @Override
    @Transactional
    public SessionPolicyDetails update(UUID id, SessionPolicyUpdate update) {
        SessionPolicy policy = tierGuard.requireInTier(repository.findById(id), () -> new NotFoundException("policy not found"));

        String reauthFactors = validateReauthFactors(update.reauthFactors());
        String stepUpFactors = validateReauthFactors(update.stepUpFactors());
        List<IpRuleEntry> ipRules = toIpRules(update.ipRules()); // validates zone references before any write
        String cookieSameSite = effectiveCookieSameSite(update.cookieSameSite(),
                policy.getOrgId() == null && DEFAULT_NAME.equals(policy.getName()));

        if (isDefaultFallback(policy)) {
            // The Default (global OR a tenant's per-org Default) is the unconditional catch-all: it stays
            // UNASSIGNED (an all-subjects binding) and keeps its priority so it always covers every user not
            // matched by a higher-priority policy — an admin can never strand users by targeting it at a
            // specific (or empty) set. Only its knobs (timeouts, factors, cookie/IP settings) are editable.
            policy.update(rulesOf(update, reauthFactors, stepUpFactors, cookieSameSite));
            applyAssignmentScope(policy, Set.of(), Set.of(), Set.of());
        } else {
            requirePriorityAvailable(update.priority(), policy.getOrgId(), policy.getId());
            policy.updatePriority(update.priority());
            if (update.enabled()) {
                policy.enable();
            } else {
                policy.disable();
            }
            policy.update(rulesOf(update, reauthFactors, stepUpFactors, cookieSameSite));
            Set<UUID> userIds = update.userIds() == null ? Set.of() : Set.copyOf(update.userIds());
            Set<UUID> roleIds = update.roleIds() == null ? Set.of() : Set.copyOf(update.roleIds());
            applyAssignmentScope(policy, userIds, roleIds, predicatesOf(update.attributePredicates()));
        }
        // IP rules are policy config (not an assignment) — the Default may carry them too (global restriction).
        replaceIpRules(id, ipRules);

        SessionPolicy saved = repository.save(policy);
        events.publishEvent(new SessionPolicyCacheChanged());

        return new CachedSessionPolicy(saved, toSpecs(ipRules));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        SessionPolicy policy = tierGuard.requireInTier(repository.findById(id), () -> new NotFoundException("policy not found"));
        if (isDefaultFallback(policy)) {
            throw BadRequestException.of("session.policy.defaultNoDelete");
        }

        // Remove this policy's own PORTAL/user assignment bindings and its IP rules before the owner (no cascade).
        sessionBindings.clearForPolicy(id);
        policyIpRules.deleteByPolicyId(id);
        try {
            // A policy still referenced by ANOTHER policy_binding (ON DELETE RESTRICT) — e.g. it governs an admin
            // console or an app — must not be deleted; that would silently drop that binding's posture. Refuse.
            repository.delete(policy);
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            throw ConflictException.of("session.policy.inUseByBinding");
        }
        events.publishEvent(new SessionPolicyCacheChanged());
    }

    // Duplicate-name check within the acting tier (partial-unique indexes make the global name and each
    // org's names unique within their own tier).
    private boolean existsInTier(String name, UUID org) {
        return (org == null
                ? repository.findByNameAndOrgIdIsNull(name)
                : repository.findByNameAndOrgId(name, org)).isPresent();
    }

    /**
     * Priority is UNIQUE within a tier (each tenant's own set + the global set): the session resolution breaks a
     * same-specificity tie on priority, so two competing policies must never share one — else the winner would
     * fall to an arbitrary id order. Reject a create/update that would collide with another policy ({@code selfId}
     * is the policy being updated, excluded; null on create).
     */
    private void requirePriorityAvailable(int priority, UUID org, UUID selfId) {
        List<SessionPolicy> atPriority = org == null
                ? repository.findByPriorityAndOrgIdIsNull(priority)
                : repository.findByPriorityAndOrgId(priority, org);
        if (atPriority.stream().anyMatch(p -> !p.getId().equals(selfId))) {
            throw ConflictException.of("session.policy.priority.duplicate");
        }
    }

    // A "Default" policy — the GLOBAL fallback or a tenant's provisioned per-org Default — is the tier's
    // unconditional lowest-priority catch-all. It must stay unassigned + non-reprioritisable + non-deletable
    // so it always covers everyone not matched by a higher-priority policy, and an admin can never leave users
    // with no policy by mis-targeting it.
    private boolean isDefaultFallback(SessionPolicy policy) {
        return DEFAULT_NAME.equals(policy.getName());
    }

    /**
     * Write the policy's assignment scope into the {@code policy_binding} matrix: validate that every targeted
     * subject is assignable (same-org or global), then hand the whole scope to {@link SessionBindings}, which
     * reconciles the all-subjects/per-subject bindings.
     */
    private void applyAssignmentScope(SessionPolicy policy, Set<UUID> userIds, Set<UUID> roleIds,
            Set<AttributePredicateGroup> attributes) {
        for (UUID userId : userIds) {
            requireAssignable(policy, users.orgIdOf(userId), "user");
        }
        for (UUID roleId : roleIds) {
            requireAssignable(policy, roles.orgIdOf(roleId), "role");
        }
        // A predicate carries no cross-org subject to validate: it is stamped and resolved in the acting tier
        // (RLS), so it only ever matches the tenant's own users.
        sessionBindings.replaceForPolicy(policy.getId(), policy.getPriority(), userIds, roleIds, attributes);
    }

    private Set<AttributePredicateGroup> predicatesOf(Set<AttributePredicateGroup> predicates) {
        Set<AttributePredicateGroup> groups = predicates == null ? Set.of() : Set.copyOf(predicates);
        groups.forEach(this::requireTargetable);
        return groups;
    }

    /** Defence in depth beyond the request DTO's {@code @AssertTrue}: a policy target may only use an operator on
     *  the targetable allow-list (guarding a future operator not yet admitted), else a spec built outside the API
     *  would 500 on the child-table CHECK instead of a clean 400. */
    private void requireTargetable(AttributePredicateGroup group) {
        group.firstNonTargetableOperator().ifPresent(operator -> {
            throw new BadRequestException("operator " + operator + " cannot target a policy");
        });
    }

    /**
     * A policy may target a subject that is GLOBAL (org null — e.g. the shared ROLE_USER) or belongs to the
     * policy's OWN org; never another tenant's user or role. This stops a tenant admin from binding a policy to
     * a foreign-tenant principal — a reference RLS would leave inert at resolution but which should not exist.
     */
    private void requireAssignable(SessionPolicy policy, Optional<UUID> subjectOrg, String kind) {
        UUID org = subjectOrg.orElse(null);
        // Org-agnostic message: don't confirm the rejected id belongs to ANOTHER org (a foreign-tenant hint).
        if (org != null && !Objects.equals(org, policy.getOrgId())) {
            throw BadRequestException.of("session.policy.assignment.notAllowed", kind);
        }
    }

    /** Replaces the policy's IP rules: delete the dropped rows, insert the newly added ones. */
    private void replaceIpRules(UUID policyId, List<IpRuleEntry> desired) {
        List<SessionPolicyIpRule> current = policyIpRules.findByPolicyId(policyId);
        Set<IpRuleEntry> keep = Set.copyOf(desired);
        current.stream().filter(row -> !keep.contains(row.toEntry())).forEach(policyIpRules::delete);
        Set<IpRuleEntry> present = current.stream().map(SessionPolicyIpRule::toEntry).collect(Collectors.toSet());
        desired.stream().filter(rule -> !present.contains(rule))
                .forEach(rule -> policyIpRules.save(new SessionPolicyIpRule(policyId, rule)));
    }

    /** The rules value object for an update — built at the layer boundary, never positionally in the entity. */
    private SessionRules rulesOf(SessionPolicyUpdate update, String reauthFactors, String stepUpFactors,
                                 String cookieSameSite) {
        return new SessionRules(update.absoluteTimeoutMinutes(), update.idleTimeoutMinutes(),
                update.reauthIntervalMinutes(), reauthFactors, update.sensitiveReauthWindowMinutes(),
                stepUpFactors, update.bindClient(), update.maxConcurrentSessions(), update.rotateOnReauth(),
                cookieSameSite);
    }

    /**
     * The SESSION cookie is written before the request's user (and therefore their policy) is known, so
     * {@code PolicyAwareCookieSerializer} reads the GLOBAL Default's SameSite for every session. Any other
     * policy therefore stores the SAME value: a divergent one would be inert (edited, never applied), and a
     * stale one submitted by a client must not be persisted as if it meant something. Only the global Default
     * actually decides the attribute.
     */
    private String effectiveCookieSameSite(String requested, boolean isGlobalDefault) {
        if (isGlobalDefault) {
            return requested;
        }
        return cached.stream()
                .filter(p -> p.policy().getOrgId() == null && DEFAULT_NAME.equals(p.getName()))
                .findFirst()
                .map(SessionPolicyDetails::getCookieSameSite)
                .orElse(requested); // no global Default yet (seeding): keep what was asked for
    }
}

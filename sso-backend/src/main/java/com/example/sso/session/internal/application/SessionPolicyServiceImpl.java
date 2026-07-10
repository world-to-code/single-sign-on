package com.example.sso.session.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.session.IpRuleSpec;
import com.example.sso.session.NetworkZoneService;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.session.SessionPolicySpec;
import com.example.sso.session.SessionPolicyUpdate;
import com.example.sso.session.internal.domain.IpAction;
import com.example.sso.session.internal.domain.IpRuleEntry;
import com.example.sso.session.internal.domain.SessionPolicy;
import com.example.sso.session.internal.domain.SessionRules;
import com.example.sso.session.internal.domain.SessionPolicyIpRule;
import com.example.sso.session.internal.domain.SessionPolicyIpRuleRepository;
import com.example.sso.session.internal.domain.SessionPolicyRepository;
import com.example.sso.session.internal.domain.SessionPolicyRole;
import com.example.sso.session.internal.domain.SessionPolicyRoleRepository;
import com.example.sso.session.internal.domain.SessionPolicyUser;
import com.example.sso.session.internal.domain.SessionPolicyUserRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.RoleRef;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
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
 * <p>The user/role assignments and IP rules are stored as explicit child rows
 * ({@link SessionPolicyUser}/{@link SessionPolicyRole}/{@link SessionPolicyIpRule}); this service issues each
 * insert/delete itself (whole-set replaces compute the diff), so the code shows exactly which rows change.
 */
@Service
@RequiredArgsConstructor
public class SessionPolicyServiceImpl implements SessionPolicyService {

    private final SessionPolicyRepository repository;
    private final SessionPolicyUserRepository policyUsers;
    private final SessionPolicyRoleRepository policyRoles;
    private final SessionPolicyIpRuleRepository policyIpRules;
    private final UserService users;
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

    /** Loads every policy with its child rows in a fixed number of queries (no per-policy N+1). */
    private List<CachedSessionPolicy> loadAll() {
        Map<UUID, Set<UUID>> usersByPolicy = policyUsers.findAll().stream()
                .collect(Collectors.groupingBy(SessionPolicyUser::policyId,
                        Collectors.mapping(SessionPolicyUser::userId, Collectors.toSet())));
        Map<UUID, Set<UUID>> rolesByPolicy = policyRoles.findAll().stream()
                .collect(Collectors.groupingBy(SessionPolicyRole::policyId,
                        Collectors.mapping(SessionPolicyRole::roleId, Collectors.toSet())));
        Map<UUID, List<IpRuleEntry>> ipRulesByPolicy = policyIpRules.findAll().stream()
                .collect(Collectors.groupingBy(SessionPolicyIpRule::policyId,
                        Collectors.mapping(SessionPolicyIpRule::toEntry, Collectors.toList())));

        return repository.findAllByOrderByPriorityDesc().stream()
                .map(policy -> new CachedSessionPolicy(policy,
                        usersByPolicy.getOrDefault(policy.getId(), Set.of()),
                        rolesByPolicy.getOrDefault(policy.getId(), Set.of()),
                        toSpecs(ipRulesByPolicy.getOrDefault(policy.getId(), List.of()))))
                .toList();
    }

    // --- Read path (served from the cache) ---

    @Override
    public SessionPolicyDetails resolveForUser(UserAccount user) {
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());
        UUID currentOrg = orgContext.currentOrg().orElse(null);

        return cached.stream()
                .filter(SessionPolicyDetails::isEnabled)
                .filter(p -> inScope(p, currentOrg))
                .filter(p -> appliesTo(p, user.getId(), roleIds))
                .max(Comparator.comparingInt(SessionPolicyDetails::getPriority))
                .<SessionPolicyDetails>map(p -> p)
                .orElseGet(this::defaultPolicy);
    }

    // A policy applies to a request only if it is GLOBAL (org_id null) or owned by the request's bound org.
    // With no org bound (e.g. an unauthenticated chain) only global policies apply — never another tenant's.
    private boolean inScope(CachedSessionPolicy p, UUID currentOrg) {
        UUID orgId = p.policy().getOrgId();
        return orgId == null || orgId.equals(currentOrg);
    }

    @Override
    public SessionPolicyDetails resolveForUsername(String username) {
        return users.findByUsername(username)
                .map(this::resolveForUser)
                .orElseGet(this::defaultPolicy);
    }

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

    private boolean appliesTo(SessionPolicyDetails p, UUID userId, Set<UUID> roleIds) {
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
            repository.saveAndFlush(new SessionPolicy(DEFAULT_NAME, TENANT_DEFAULT_PRIORITY, orgId));
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
                throw new BadRequestException("invalid zone id: " + r.zoneId());
            }
            if (!networkZones.exists(zoneId)) {
                throw new BadRequestException("unknown network zone: " + r.zoneId());
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
            throw new ConflictException("policy name already exists");
        }

        String reauthFactors = validateReauthFactors(spec.reauthFactors());
        String stepUpFactors = validateReauthFactors(spec.stepUpFactors());
        Set<UUID> userIds = spec.userIds() == null ? Set.of() : Set.copyOf(spec.userIds());
        Set<UUID> roleIds = spec.roleIds() == null ? Set.of() : Set.copyOf(spec.roleIds());
        List<IpRuleEntry> ipRules = toIpRules(spec.ipRules()); // validates zone references before any write
        String cookieSameSite = effectiveCookieSameSite(spec.cookieSameSite(),
                creationOrg == null && DEFAULT_NAME.equals(spec.name()));

        SessionPolicy policy = new SessionPolicy(spec.name(), spec.priority(), creationOrg);
        if (!spec.enabled()) {
            policy.disable();
        }
        policy.update(new SessionRules(spec.absoluteTimeoutMinutes(), spec.idleTimeoutMinutes(),
                spec.reauthIntervalMinutes(), reauthFactors, spec.sensitiveReauthWindowMinutes(), stepUpFactors,
                spec.bindClient(), spec.maxConcurrentSessions(), spec.rotateOnReauth(), cookieSameSite,
                spec.elevationTokenTtlMinutes(), normalizeCidrs(spec.adminAllowedCidrs())));
        SessionPolicy saved = repository.save(policy);

        replaceUsers(saved.getId(), userIds);
        replaceRoles(saved.getId(), roleIds);
        replaceIpRules(saved.getId(), ipRules);
        events.publishEvent(new SessionPolicyCacheChanged());

        return new CachedSessionPolicy(saved, userIds, roleIds, toSpecs(ipRules));
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

        boolean isDefault = isDefaultFallback(policy);
        Set<UUID> userIds;
        Set<UUID> roleIds;
        if (isDefault) {
            // The Default (global OR a tenant's per-org Default) is the unconditional catch-all: it stays
            // UNASSIGNED and keeps its priority so it always covers every user not matched by a higher-priority
            // policy — an admin can never strand users by targeting it at a specific (or empty) set. Only its
            // knobs (timeouts, factors, cookie/IP settings) are editable.
            policy.update(rulesOf(update, reauthFactors, stepUpFactors, cookieSameSite));
            userIds = currentUserIds(id);
            roleIds = currentRoleIds(id);
        } else {
            policy.updatePriority(update.priority());
            if (update.enabled()) {
                policy.enable();
            } else {
                policy.disable();
            }
            policy.update(rulesOf(update, reauthFactors, stepUpFactors, cookieSameSite));
            userIds = update.userIds() == null ? Set.of() : Set.copyOf(update.userIds());
            roleIds = update.roleIds() == null ? Set.of() : Set.copyOf(update.roleIds());
            replaceUsers(id, userIds);
            replaceRoles(id, roleIds);
        }
        // IP rules are policy config (not an assignment) — the Default may carry them too (global restriction).
        replaceIpRules(id, ipRules);

        SessionPolicy saved = repository.save(policy);
        events.publishEvent(new SessionPolicyCacheChanged());

        return new CachedSessionPolicy(saved, userIds, roleIds, toSpecs(ipRules));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        SessionPolicy policy = tierGuard.requireInTier(repository.findById(id), () -> new NotFoundException("policy not found"));
        if (isDefaultFallback(policy)) {
            throw new BadRequestException("the Default policy cannot be deleted");
        }

        // Explicitly remove the child rows before the owner (no JPA cascade).
        policyUsers.deleteByPolicyId(id);
        policyRoles.deleteByPolicyId(id);
        policyIpRules.deleteByPolicyId(id);
        try {
            // A policy governing an admin console is referenced by admin_portal_settings (ON DELETE RESTRICT):
            // deleting it would silently revert that console to the acting admin's policy, dropping the tenant's
            // admin IP allowlist. Refuse, and say so, instead of failing open.
            repository.delete(policy);
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("this policy governs the admin console; select another one first");
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

    // A "Default" policy — the GLOBAL fallback or a tenant's provisioned per-org Default — is the tier's
    // unconditional lowest-priority catch-all. It must stay unassigned + non-reprioritisable + non-deletable
    // so it always covers everyone not matched by a higher-priority policy, and an admin can never leave users
    // with no policy by mis-targeting it.
    private boolean isDefaultFallback(SessionPolicy policy) {
        return DEFAULT_NAME.equals(policy.getName());
    }

    private Set<UUID> currentUserIds(UUID policyId) {
        return policyUsers.findByPolicyId(policyId).stream().map(SessionPolicyUser::userId)
                .collect(Collectors.toSet());
    }

    private Set<UUID> currentRoleIds(UUID policyId) {
        return policyRoles.findByPolicyId(policyId).stream().map(SessionPolicyRole::roleId)
                .collect(Collectors.toSet());
    }

    /** Replaces the policy's user assignments: delete the dropped rows, insert the newly added ones. */
    private void replaceUsers(UUID policyId, Set<UUID> desired) {
        List<SessionPolicyUser> current = policyUsers.findByPolicyId(policyId);
        current.stream().filter(row -> !desired.contains(row.userId())).forEach(policyUsers::delete);
        Set<UUID> present = current.stream().map(SessionPolicyUser::userId).collect(Collectors.toSet());
        desired.stream().filter(userId -> !present.contains(userId))
                .forEach(userId -> policyUsers.save(new SessionPolicyUser(policyId, userId)));
    }

    /** Replaces the policy's role assignments: delete the dropped rows, insert the newly added ones. */
    private void replaceRoles(UUID policyId, Set<UUID> desired) {
        List<SessionPolicyRole> current = policyRoles.findByPolicyId(policyId);
        current.stream().filter(row -> !desired.contains(row.roleId())).forEach(policyRoles::delete);
        Set<UUID> present = current.stream().map(SessionPolicyRole::roleId).collect(Collectors.toSet());
        desired.stream().filter(roleId -> !present.contains(roleId))
                .forEach(roleId -> policyRoles.save(new SessionPolicyRole(policyId, roleId)));
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
                cookieSameSite, update.elevationTokenTtlMinutes(),
                normalizeCidrs(update.adminAllowedCidrs()));
    }

    /** Trims and validates each admin-console CIDR (rejecting an invalid one, 400); blank -> null (any network). */
    private String normalizeCidrs(String cidrs) {
        if (cidrs == null || cidrs.isBlank()) {
            return null;
        }
        List<String> cleaned = Arrays.stream(cidrs.split(","))
                .map(String::trim).filter(cidr -> !cidr.isEmpty()).toList();
        cleaned.forEach(this::validateCidr);
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    private void validateCidr(String cidr) {
        try {
            new IpAddressMatcher(cidr);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid CIDR: " + cidr);
        }
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

package com.example.sso.session.internal.application;

import com.example.sso.session.NetworkZoneService;
import com.example.sso.session.NetworkZoneSpec;
import com.example.sso.session.NetworkZoneView;
import com.example.sso.session.internal.domain.NetworkZone;
import com.example.sso.session.internal.domain.NetworkZoneCidr;
import com.example.sso.session.internal.domain.NetworkZoneCidrRepository;
import com.example.sso.session.internal.domain.NetworkZoneRepository;
import com.example.sso.session.internal.domain.SessionPolicyIpRuleRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link NetworkZoneService}. Owns the zone catalog CRUD and a volatile cache of
 * {@code zoneId → CIDRs}, refreshed on every mutation, so {@link #cidrsForZone} is a cheap in-memory lookup
 * on the request path (no DB round-trip). A zone that is referenced by a session policy cannot be deleted, so
 * a Block rule never silently turns inert.
 *
 * <p>The zone's CIDRs live in explicit {@link NetworkZoneCidr} rows: this service issues each insert/delete
 * itself (a whole-set replace computes the diff), so reading the code shows exactly which rows change.
 */
@Service
@RequiredArgsConstructor
public class NetworkZoneServiceImpl implements NetworkZoneService {

    private final NetworkZoneRepository repository;
    private final NetworkZoneCidrRepository cidrs;
    private final SessionPolicyIpRuleRepository policyIpRules;
    private final OrgContext orgContext;
    private final OrgTierGuard tierGuard;
    private final ApplicationEventPublisher events;
    private volatile Map<UUID, List<String>> cache = Map.of();

    @PostConstruct
    public void load() {
        reload();
    }

    /**
     * Rebuilds the zone→CIDR cache in the PLATFORM context so it holds EVERY tenant's zones: a session
     * policy's IP rule (resolved on the request path via {@link #cidrsForZone}) may reference any zone the
     * request's org can see, so the lookup table must be cross-org. The CIDR rows live in the (unguarded)
     * {@code network_zone_cidr} child table; the platform context keeps the read cross-org regardless.
     */
    private void reload() {
        this.cache = orgContext.callAsPlatform(this::groupCidrsByZone);
    }

    /** Rebuild AFTER the mutating transaction commits, so the reload reads the committed cross-org rows. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onCacheChanged(NetworkZoneCacheChanged event) {
        reload();
    }

    private Map<UUID, List<String>> groupCidrsByZone() {
        return cidrs.findAll().stream()
                .collect(Collectors.groupingBy(NetworkZoneCidr::zoneId,
                        Collectors.mapping(NetworkZoneCidr::cidr, Collectors.toList())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<NetworkZoneView> list() {
        // Tier-scoped: a tenant admin (tier = their org) sees ONLY their org's zones, NOT the GLOBAL ones RLS
        // keeps visible (and which they cannot edit — update/delete requireInTier 404s them). Platform admin
        // (tier null) sees the globals.
        UUID tier = tierGuard.currentTier();
        Map<UUID, List<String>> byZone = groupCidrsByZone();
        return repository.findAll().stream()
                .filter(zone -> Objects.equals(zone.getOrgId(), tier))
                .sorted(Comparator.comparing(NetworkZone::getName, String.CASE_INSENSITIVE_ORDER))
                .map(zone -> NetworkZoneView.of(zone, byZone.getOrDefault(zone.getId(), List.of())))
                .toList();
    }

    @Override
    @Transactional
    public NetworkZoneView create(NetworkZoneSpec spec) {
        UUID creationOrg = tierGuard.currentTier();
        if (findInTier(spec.name(), creationOrg).isPresent()) {
            throw new ConflictException("zone name already exists");
        }
        List<String> validated = validateCidrs(spec.cidrs());
        NetworkZone zone = repository.save(new NetworkZone(spec.name(), spec.description(), creationOrg));
        validated.forEach(cidr -> cidrs.save(new NetworkZoneCidr(zone.getId(), cidr)));
        events.publishEvent(new NetworkZoneCacheChanged());
        return NetworkZoneView.of(zone, validated);
    }

    @Override
    @Transactional
    public NetworkZoneView update(UUID id, NetworkZoneSpec spec) {
        NetworkZone zone = tierGuard.requireInTier(repository.findById(id), () -> new NotFoundException("zone not found"));
        findInTier(spec.name(), tierGuard.currentTier())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw new ConflictException("zone name already exists"); });
        List<String> validated = validateCidrs(spec.cidrs());
        zone.update(spec.name(), spec.description());
        replaceCidrs(id, validated);
        NetworkZone saved = repository.save(zone);
        events.publishEvent(new NetworkZoneCacheChanged());
        return NetworkZoneView.of(saved, validated);
    }

    /** Replaces the zone's CIDR set: delete the rows dropped from the set, insert the newly added ones. */
    private void replaceCidrs(UUID zoneId, List<String> desired) {
        List<NetworkZoneCidr> current = cidrs.findByZoneId(zoneId);
        Set<String> keep = Set.copyOf(desired);
        current.stream().filter(row -> !keep.contains(row.cidr())).forEach(cidrs::delete);
        Set<String> present = current.stream().map(NetworkZoneCidr::cidr).collect(Collectors.toSet());
        desired.stream().filter(cidr -> !present.contains(cidr))
                .forEach(cidr -> cidrs.save(new NetworkZoneCidr(zoneId, cidr)));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        NetworkZone zone = tierGuard.requireInTier(repository.findById(id), () -> new NotFoundException("zone not found"));
        if (policyIpRules.countByZoneId(id) > 0) {
            throw new ConflictException("zone is referenced by a session policy; remove those rules first");
        }
        cidrs.deleteByZoneId(id); // explicitly remove the child rows before the owner (no JPA cascade)
        try {
            repository.delete(zone);
            repository.flush(); // surface the FK RESTRICT here, inside the try, not at commit
        } catch (DataIntegrityViolationException e) {
            // TOCTOU backstop: a policy referenced the zone between the count and the delete — the DB FK
            // refused. Same outcome as the guard above, so report the same 409, not a 500.
            throw new ConflictException("zone is referenced by a session policy; remove those rules first");
        }
        events.publishEvent(new NetworkZoneCacheChanged());
    }

    // Name lookup within the acting tier (partial-unique indexes keep the global name and each org's names
    // unique within their own tier); returns the row so the rename check can exclude the zone itself.
    private Optional<NetworkZone> findInTier(String name, UUID org) {
        return org == null ? repository.findByNameAndOrgIdIsNull(name) : repository.findByNameAndOrgId(name, org);
    }

    @Override
    public List<String> cidrsForZone(UUID zoneId) {
        return cache.getOrDefault(zoneId, List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID zoneId) {
        return repository.existsById(zoneId);
    }

    /** Every CIDR must parse (Spring's matcher) and a zone must cover at least one range. */
    private List<String> validateCidrs(List<String> cidrs) {
        List<String> trimmed = cidrs == null ? List.of()
                : cidrs.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (trimmed.isEmpty()) {
            throw new BadRequestException("a network zone needs at least one CIDR");
        }
        for (String cidr : trimmed) {
            try {
                new IpAddressMatcher(cidr);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("invalid CIDR: " + cidr);
            }
        }
        return trimmed;
    }
}

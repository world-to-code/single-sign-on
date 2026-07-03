package com.example.sso.session.internal.application;

import com.example.sso.session.NetworkZoneService;
import com.example.sso.session.NetworkZoneSpec;
import com.example.sso.session.NetworkZoneView;
import com.example.sso.session.internal.domain.NetworkZone;
import com.example.sso.session.internal.domain.NetworkZoneRepository;
import com.example.sso.session.internal.domain.SessionPolicyRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link NetworkZoneService}. Owns the zone catalog CRUD and a volatile cache of
 * {@code zoneId → CIDRs}, refreshed on every mutation, so {@link #cidrsForZone} is a cheap in-memory lookup
 * on the request path (no DB round-trip / LazyInitializationException). A zone that is referenced by a
 * session policy cannot be deleted, so a Block rule never silently turns inert.
 */
@Service
@RequiredArgsConstructor
public class NetworkZoneServiceImpl implements NetworkZoneService {

    private final NetworkZoneRepository repository;
    private final SessionPolicyRepository policies;
    private volatile Map<UUID, List<String>> cache = Map.of();

    // No @Transactional: lifecycle callbacks bypass the proxy anyway; findAllWithCidrs opens its own tx
    // and fetch-joins the CIDRs, so the detached entities are fully initialized.
    @PostConstruct
    public void load() {
        refresh();
    }

    private void refresh() {
        this.cache = repository.findAllWithCidrs().stream()
                .collect(Collectors.toUnmodifiableMap(NetworkZone::getId, NetworkZone::cidrList));
    }

    @Override
    @Transactional(readOnly = true)
    public List<NetworkZoneView> list() {
        return repository.findAllWithCidrs().stream()
                .sorted(Comparator.comparing(NetworkZone::getName, String.CASE_INSENSITIVE_ORDER))
                .map(NetworkZoneView::of)
                .toList();
    }

    @Override
    @Transactional
    public NetworkZoneView create(NetworkZoneSpec spec) {
        if (repository.findByName(spec.name()).isPresent()) {
            throw new ConflictException("zone name already exists");
        }
        List<String> cidrs = validateCidrs(spec.cidrs());
        NetworkZoneView view = NetworkZoneView.of(
                repository.save(new NetworkZone(spec.name(), spec.description(), cidrs)));
        refresh();
        return view;
    }

    @Override
    @Transactional
    public NetworkZoneView update(UUID id, NetworkZoneSpec spec) {
        NetworkZone zone = repository.findById(id).orElseThrow(() -> new NotFoundException("zone not found"));
        repository.findByName(spec.name())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw new ConflictException("zone name already exists"); });
        List<String> cidrs = validateCidrs(spec.cidrs());
        zone.update(spec.name(), spec.description(), cidrs);
        NetworkZoneView view = NetworkZoneView.of(repository.save(zone));
        refresh();
        return view;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        NetworkZone zone = repository.findById(id).orElseThrow(() -> new NotFoundException("zone not found"));
        if (policies.countReferencingZone(id) > 0) {
            throw new ConflictException("zone is referenced by a session policy; remove those rules first");
        }
        try {
            repository.delete(zone);
            repository.flush(); // surface the FK RESTRICT here, inside the try, not at commit
        } catch (DataIntegrityViolationException e) {
            // TOCTOU backstop: a policy referenced the zone between the count and the delete — the DB FK
            // refused. Same outcome as the guard above, so report the same 409, not a 500.
            throw new ConflictException("zone is referenced by a session policy; remove those rules first");
        }
        refresh();
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

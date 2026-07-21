package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.metadata.internal.domain.ProfileEntity;
import com.example.sso.metadata.internal.domain.ProfileRepository;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link ProfileService}. Every read names the acting organization explicitly rather than leaning on
 * ambient RLS, and a caller bound to no organization gets nothing — a profile always belongs to a tenant, so
 * "no organization" is not a tier here, it is an absence.
 */
@Service
@RequiredArgsConstructor
class ProfileServiceImpl implements ProfileService {

    private final ProfileRepository repository;
    private final OrganizationService organizations;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public List<Profile> list() {
        UUID org = actingOrg().orElse(null);
        if (org == null) {
            return List.of(); // a profile always belongs to a tenant, so "no organization" is an absence
        }
        return repository.findByOrgIdOrderByName(org).stream().map(this::toProfile).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Profile> findById(UUID id) {
        return actingOrg().flatMap(org -> repository.findByIdAndOrgId(id, org)).map(this::toProfile);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Profile> findByConnectorId(UUID connectorId) {
        return actingOrg().flatMap(org -> repository.findByConnectorIdAndOrgId(connectorId, org))
                .map(this::toProfile);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Profile> tenantProfile() {
        return actingOrg().flatMap(org -> repository.findByOrgIdAndKind(org, ProfileKind.TENANT))
                .map(this::toProfile);
    }

    @Override
    @Transactional
    public Profile provisionForConnector(UUID connectorId, String name, ProfileKind kind) {
        UUID org = actingOrg()
                .orElseThrow(() -> new ForbiddenException("Profiles belong to an organization."));
        // Idempotent: saving a connector again must not mint a second schema for the same directory.
        return repository.findByConnectorIdAndOrgId(connectorId, org)
                .map(this::toProfile)
                .orElseGet(() -> toProfile(repository.saveAndFlush(
                        ProfileEntity.forConnector(org, uniqueName(org, name), kind, connectorId))));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Profile provisionForSource(UUID orgId, ProfileKind kind, String name) {
        // Bound explicitly rather than read from the ambient context: this runs from the org-created listener,
        // where nothing is bound, and RLS has to scope both the existence check and the insert's WITH CHECK.
        return orgContext.callInOrg(orgId, () -> repository.findByOrgIdAndKind(orgId, kind)
                .map(this::toProfile)
                .orElseGet(() -> toProfile(repository.saveAndFlush(
                        ProfileEntity.forSource(orgId, uniqueName(orgId, name), kind)))));
    }

    /** Profile names are unique per organization; a connector's display name may already be taken. */
    private String uniqueName(UUID org, String name) {
        String candidate = name;
        for (int suffix = 2; repository.findByOrgIdAndName(org, candidate).isPresent(); suffix++) {
            candidate = name + " (" + suffix + ")";
        }
        return candidate;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provisionDefault(UUID orgId) {
        // REQUIRES_NEW because this runs from an AFTER_COMMIT listener, where the creating transaction is
        // already completing and a plain REQUIRES would find none. runInOrg wraps the whole read+write so RLS
        // scopes the existence check AND the insert's WITH CHECK; saveAndFlush forces the INSERT while the GUC
        // still names this org (a deferred flush would run after the scope restored and fail).
        String name = organizations.findView(orgId).map(OrganizationView::slug)
                .orElseThrow(() -> new IllegalStateException("provisioning a profile for an unknown organization"));
        orgContext.runInOrg(orgId, () -> {
            if (repository.findByOrgIdAndName(orgId, name).isPresent()) {
                return; // idempotent: a re-delivered event, or a re-run to heal a failure
            }
            repository.saveAndFlush(ProfileEntity.tenantDefault(orgId, name));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> connectorIdsOf(Collection<UUID> profileIds) {
        if (profileIds == null || profileIds.isEmpty()) {
            return Set.of();
        }
        UUID org = actingOrg().orElse(null);
        if (org == null) {
            return Set.of();
        }
        // Re-filtered by org: this answers a provenance question, so a foreign id must contribute nothing.
        return repository.findAllById(profileIds).stream()
                .filter(profile -> org.equals(profile.getOrgId()))
                .map(ProfileEntity::getConnectorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** The organization whose profiles the caller may see; empty when bound to none. */
    private Optional<UUID> actingOrg() {
        return orgContext.currentOrg();
    }

    private Profile toProfile(ProfileEntity row) {
        return new Profile(row.getId(), row.getName(), row.getKind(), row.getConnectorId(), row.isSystem(),
                row.isDefaultForCreation());
    }
}

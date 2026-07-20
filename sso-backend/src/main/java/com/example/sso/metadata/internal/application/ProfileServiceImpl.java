package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileService;
import com.example.sso.metadata.internal.domain.ProfileEntity;
import com.example.sso.metadata.internal.domain.ProfileRepository;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
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
        return actingOrg().map(org -> repository.findByOrgIdOrderByName(org).stream().map(this::toProfile).toList())
                .orElseGet(List::of);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Profile> findById(UUID id) {
        return actingOrg().flatMap(org -> repository.findByIdAndOrgId(id, org)).map(this::toProfile);
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

    /** The organization whose profiles the caller may see; empty when bound to none. */
    private Optional<UUID> actingOrg() {
        return orgContext.currentOrg();
    }

    private Profile toProfile(ProfileEntity row) {
        return new Profile(row.getId(), row.getName(), row.getKind(), row.getConnectorId(), row.isSystem(),
                row.isDefaultForCreation());
    }
}

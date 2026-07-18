package com.example.sso.branding.internal.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Explicit-tier access to {@link OrgBranding}: {@code findByOrgId} for a tenant's own row, {@code
 * findByOrgIdIsNull} for the platform-wide default. Both filter explicitly (never ambient RLS) so resolution
 * is correct under any bound context.
 */
public interface OrgBrandingRepository extends JpaRepository<OrgBranding, UUID> {

    Optional<OrgBranding> findByOrgId(UUID orgId);

    Optional<OrgBranding> findByOrgIdIsNull();
}

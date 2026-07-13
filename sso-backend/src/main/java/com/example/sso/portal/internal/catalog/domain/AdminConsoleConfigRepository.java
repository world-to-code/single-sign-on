package com.example.sso.portal.internal.catalog.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminConsoleConfigRepository extends JpaRepository<AdminConsoleConfig, UUID> {

    /** A specific tenant's own console config (RLS-scoped: the caller's tenant plus GLOBAL rows). */
    Optional<AdminConsoleConfig> findByOrgId(UUID orgId);

    /** The GLOBAL default console config (org_id NULL) tenants inherit. */
    Optional<AdminConsoleConfig> findByOrgIdIsNull();
}

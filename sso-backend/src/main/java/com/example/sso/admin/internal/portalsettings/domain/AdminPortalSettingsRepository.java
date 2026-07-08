package com.example.sso.admin.internal.portalsettings.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminPortalSettingsRepository extends JpaRepository<AdminPortalSettings, UUID> {

    /** One tenant's own settings row, if it has saved any. */
    Optional<AdminPortalSettings> findByOrgId(UUID orgId);

    /** The global default row (org_id NULL) every tenant inherits until it customizes. */
    Optional<AdminPortalSettings> findByOrgIdIsNull();
}

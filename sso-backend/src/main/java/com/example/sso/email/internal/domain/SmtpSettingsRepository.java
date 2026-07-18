package com.example.sso.email.internal.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Explicit-tier access to {@link SmtpSettings}: {@code findByOrgId} for a tenant's own row, {@code
 * findByOrgIdIsNull} for the platform-wide override. Both are used (never ambient RLS) so resolution is correct
 * under any bound context.
 */
public interface SmtpSettingsRepository extends JpaRepository<SmtpSettings, UUID> {

    Optional<SmtpSettings> findByOrgId(UUID orgId);

    Optional<SmtpSettings> findByOrgIdIsNull();
}

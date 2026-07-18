package com.example.sso.email.internal.domain;

import com.example.sso.email.template.EmailEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Explicit-tier access to {@link EmailTemplate}: {@code findByOrgIdAndEvent} for a tenant's own template for an
 * event, {@code findByOrgIdIsNullAndEvent} for the platform-wide default. Both filter explicitly (never ambient
 * RLS) so resolution is correct under any bound context.
 */
public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, UUID> {

    Optional<EmailTemplate> findByOrgIdAndEvent(UUID orgId, EmailEvent event);

    Optional<EmailTemplate> findByOrgIdIsNullAndEvent(EmailEvent event);
}

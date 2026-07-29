package com.example.sso.mfa.internal.sms.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** RLS-confined access to {@code sms_settings} (a tenant sees its own row plus the platform one). */
public interface SmsSettingsRepository extends JpaRepository<SmsSettings, UUID> {

    Optional<SmsSettings> findByOrgId(UUID orgId);

    Optional<SmsSettings> findByOrgIdIsNull();
}

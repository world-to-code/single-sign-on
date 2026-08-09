package com.example.sso.audit.internal.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/** The one collector row, keyed by the constant that makes it the only one. */
public interface AuditExportSettingsRepository extends JpaRepository<AuditExportSettingsRow, Short> {
}

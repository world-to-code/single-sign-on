package com.example.sso.admin.internal.audit.api;

import com.example.sso.admin.internal.shared.application.AdminService;
import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.Audited;
import com.example.sso.audit.export.AuditExportSettingsService;
import com.example.sso.audit.export.AuditExportView;
import com.example.sso.shared.Page;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for the audit log, optionally filtered by {@link AuditCategory} tab. Read access is
 * per-category: an explicit {@code category} needs its {@code audit:read:<category>} grant, and the ALL
 * view (no category) requires at least one audit-read grant — enforced by {@code @auditAccessPolicy.canRead}
 * (deny-by-default) and re-applied in the service, which restricts the ALL result to the permitted categories.
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AdminService adminService;
    private final AuditExportSettingsService exportSettings;

    /**
     * Where the audit trail is shipped. PLATFORM-only: the exporter reads across every tenant, while
     * {@code audit:read} deliberately shows an administrator only their own org.
     */
    @GetMapping("/export")
    @RequirePermission(Permissions.AUDIT_EXPORT)
    public ResponseEntity<AuditExportView> exportSettings() {
        return exportSettings.current().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Configures the collector. Step-up: it decides where every tenant's security history goes.
     *
     * <p>Shipping the actor's and client's identifiers needs {@code audit:read:pii} ON TOP of
     * {@code audit:export}. The console redacts exactly those fields for a reader without that grant, and
     * {@code audit:export} does not imply it — so without this the export was a way to read, at full fidelity
     * and across every tenant, what the screen refuses to show. The condition only ever ADDS a requirement:
     * turning PII off is available to anyone who may configure the export at all.
     */
    @Audited(AuditType.AUDIT_EXPORT_CONFIGURED)
    @PutMapping("/export")
    @RequirePermission(Permissions.AUDIT_EXPORT)
    @PreAuthorize("@auditAccessPolicy.mayExportWithPii(#request.includePii())")
    @RequireStepUp
    public ResponseEntity<Void> configureExport(@Valid @RequestBody AuditExportRequest request) {
        exportSettings.save(request.toSettings());
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @PreAuthorize("@auditAccessPolicy.canRead(#category)")
    public Page<AuditEntry> audit(@RequestParam(name = "category", required = false) AuditCategory category,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return adminService.recentAudit(category, page, size);
    }
}

package com.example.sso.admin.internal.audit.api;

import com.example.sso.admin.internal.shared.application.AdminService;
import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditEntry;
import com.example.sso.shared.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping
    @PreAuthorize("@auditAccessPolicy.canRead(#category)")
    public Page<AuditEntry> audit(@RequestParam(name = "category", required = false) AuditCategory category,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return adminService.recentAudit(category, page, size);
    }
}

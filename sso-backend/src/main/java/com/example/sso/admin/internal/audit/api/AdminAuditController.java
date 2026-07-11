package com.example.sso.admin.internal.audit.api;

import com.example.sso.admin.internal.shared.application.AdminService;
import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditEntry;
import com.example.sso.shared.Page;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.rbac.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for the audit log, optionally filtered by {@link AuditCategory} tab. */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AdminService adminService;

    @GetMapping
    @RequirePermission(Permissions.AUDIT_READ)
    public Page<AuditEntry> audit(@RequestParam(name = "category", required = false) AuditCategory category,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return adminService.recentAudit(category, page, size);
    }
}

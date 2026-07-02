package com.example.sso.admin.internal.audit.api;

import com.example.sso.admin.internal.shared.application.AdminService;
import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditEntry;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.Permissions;
import java.util.List;
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
    public List<AuditEntry> audit(@RequestParam(name = "category", required = false) AuditCategory category) {
        return adminService.recentAudit(category);
    }
}

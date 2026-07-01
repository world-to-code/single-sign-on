package com.example.sso.admin.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Records admin-action audit events attributed to the acting administrator (resolved from the security
 * context) — the "who performed which privileged command on whom" trail. The event type drives the
 * audit category (see {@code AuditCategory}), so role/permission/group changes land under AUTHORIZATION.
 */
@Component
@RequiredArgsConstructor
public class AdminAuditLogger {

    private final AuditService audit;

    public void log(AuditType type, String detail) {
        audit.record(new AuditRecord(type, actor(), true, detail, null));
    }

    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "unknown" : authentication.getName();
    }
}

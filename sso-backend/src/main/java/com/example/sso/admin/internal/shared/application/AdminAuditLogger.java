package com.example.sso.admin.internal.shared.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
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

    /** Logs an admin action with no scopeable subject (e.g. role catalog edits) — super-admin-visible only. */
    public void log(AuditType type, String detail) {
        audit.record(new AuditRecord(type, actor(), true, detail, null));
    }

    /** Logs an admin action tagged with the subject it acts upon, so the scoped audit log can filter it. */
    public void log(AuditType type, AuditSubjectType subjectType, String subjectId, String detail) {
        audit.record(new AuditRecord(type, actor(), true, detail, null, subjectType, subjectId));
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "unknown" : authentication.getName();
    }
}

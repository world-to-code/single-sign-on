package com.example.sso.admin.internal.shared.application;

import com.example.sso.audit.AuditActor;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.tenancy.OrgContext;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Records admin-action audit events attributed to the acting administrator (resolved from the security
 * context) — the "who performed which privileged command on whom" trail. The event type drives the
 * audit category (see {@code AuditCategory}), so role/permission/group changes land under AUTHORIZATION.
 *
 * <p>Every action is tagged with the ACTING organization ({@link OrgContext}) when one is bound, so an
 * admin action performed against a tenant is attributable to that tenant — most importantly a PLATFORM
 * super-admin's action while DRILLED into a tenant (paired with the {@code ORGANIZATION_CONTEXT_ENTERED}
 * record the drill-in itself writes), giving a thorough trail of who touched which tenant's data.
 */
@Component
@RequiredArgsConstructor
public class AdminAuditLogger {

    private final AuditService audit;
    private final OrgContext orgContext;

    /** Logs an admin action with no scopeable subject (e.g. role catalog edits) — super-admin-visible only. */
    public void log(AuditType type, String detail) {
        audit.record(new AuditRecord(type, AuditActor.of(), true, withActingOrg(detail), null));
    }

    /** Logs an admin action tagged with the subject it acts upon, so the scoped audit log can filter it. */
    public void log(AuditType type, AuditSubjectType subjectType, String subjectId, String detail) {
        audit.record(new AuditRecord(type, AuditActor.of(), true, withActingOrg(detail), null, subjectType, subjectId));
    }

    /** Appends the acting org (the drilled-into tenant, or a tenant admin's own org) to the detail. */
    private String withActingOrg(String detail) {
        return orgContext.currentOrg().map(org -> detail + " actingOrg=" + org).orElse(detail);
    }
}

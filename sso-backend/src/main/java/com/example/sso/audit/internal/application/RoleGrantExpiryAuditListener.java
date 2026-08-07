package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.user.role.RoleGrantExpiredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Records a role grant that ran out of time.
 *
 * <p>Worth its own audit type: "somebody removed this role" and "this role's time was up" are different
 * answers to the same question in an investigation, and folding the second into the first would make an
 * expiry look like an administrator's decision.
 *
 * <p>The actor is deliberately the HOLDER rather than an administrator, because there is none — the record
 * says whose privilege ended, not who ended it.
 */
@Component
@RequiredArgsConstructor
class RoleGrantExpiryAuditListener {

    private final AuditService audit;

    @EventListener
    void on(RoleGrantExpiredEvent event) {
        audit.record(new AuditRecord(AuditType.ROLE_GRANT_EXPIRED, event.username(), true,
                "role grant expired: " + event.roleId(), null, event.orgId()).unverifiedActor());
    }
}

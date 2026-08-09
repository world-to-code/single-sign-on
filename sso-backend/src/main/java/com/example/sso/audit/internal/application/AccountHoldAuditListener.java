package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.user.account.AccountHeldEvent;
import com.example.sso.user.account.AccountHoldExpiredEvent;
import com.example.sso.user.account.AccountHoldLiftedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Records the life of an account hold: placed, lifted early, or run out.
 *
 * <p>All three name the HELD account as the principal — the trail says whose access was constrained, and who
 * did the constraining is enriched from the acting principal like any other administrative action. The
 * expiry has no such actor at all, so it is marked unverified rather than attributed to whoever the sweeper
 * happened to run beside.
 *
 * <p>Listening here rather than recording at each surface is what keeps a hold placed by the machine response
 * API and one placed in the console from producing two different trails — and from a third surface producing
 * none.
 */
@Component
@RequiredArgsConstructor
class AccountHoldAuditListener {

    private final AuditService audit;

    @EventListener
    void on(AccountHeldEvent event) {
        audit.record(new AuditRecord(AuditType.ACCOUNT_HELD, event.username(), true, detailOf(event), null,
                AuditSubjectType.USER, event.userId().toString(), event.orgId()));
    }

    @EventListener
    void on(AccountHoldLiftedEvent event) {
        audit.record(new AuditRecord(AuditType.ACCOUNT_HOLD_LIFTED, event.username(), true, "hold lifted", null,
                AuditSubjectType.USER, event.userId().toString(), event.orgId()));
    }

    @EventListener
    void on(AccountHoldExpiredEvent event) {
        // Nobody decided this today, the clock did — so there is no actor to attribute it to.
        audit.record(new AuditRecord(AuditType.ACCOUNT_HOLD_EXPIRED, event.username(), true, "hold expired", null,
                AuditSubjectType.USER, event.userId().toString(), event.orgId()).unverifiedActor());
    }

    /** The correlation id is carried only when a detection system asked, and it is what links the two systems. */
    private String detailOf(AccountHeldEvent event) {
        String detail = "until=" + event.expiresAt() + " reason=" + event.reason();
        return event.correlationId() == null ? detail : detail + " correlation=" + event.correlationId();
    }
}

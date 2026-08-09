package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditActor;
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
 * <p>Each names the ACTOR as the principal and the held account as the SUBJECT — the split the audit row
 * already models and the one a SIEM correlates on (OCSF keeps {@code actor.user} and the affected user
 * apart for the same reason). These rows named the held user as the actor, which made "who has been holding
 * accounts" unanswerable and would have attributed a machine's decisions to its victims.
 *
 * <p>The actor comes from {@link AuditActor}, the single home for "who performed this action": these
 * listeners run synchronously on the acting thread, so it resolves the administrator for a console hold and
 * the reserved service principal for a machine one. The expiry has no actor at all and says so.
 *
 * <p>Listening here rather than recording at each surface is what keeps a hold placed by the machine response
 * API and one placed in the console from producing two different trails — and from a third surface producing
 * none.
 */
@Component
@RequiredArgsConstructor
class AccountHoldAuditListener {

    private final AuditService audit;

    /**
     * The clock's name for the expiry. {@code system:} is the reserved prefix the actor resolver classifies as
     * SYSTEM, which is the truthful answer — better than marking the HELD user as an unverified actor, which
     * is what this did and which put the victim in the actor field.
     */
    private static final String SWEEPER = "system:account-hold";

    @EventListener
    void on(AccountHeldEvent event) {
        audit.record(new AuditRecord(AuditType.ACCOUNT_HELD, AuditActor.of(), true, detailOf(event), null,
                AuditSubjectType.USER, event.userId().toString(), event.orgId()));
    }

    @EventListener
    void on(AccountHoldLiftedEvent event) {
        audit.record(new AuditRecord(AuditType.ACCOUNT_HOLD_LIFTED, AuditActor.of(), true,
                "user=" + event.username(), null,
                AuditSubjectType.USER, event.userId().toString(), event.orgId()));
    }

    @EventListener
    void on(AccountHoldExpiredEvent event) {
        audit.record(new AuditRecord(AuditType.ACCOUNT_HOLD_EXPIRED, SWEEPER, true,
                "user=" + event.username(), null,
                AuditSubjectType.USER, event.userId().toString(), event.orgId()));
    }

    /** The correlation id is carried only when a detection system asked, and it is what links the two systems. */
    private String detailOf(AccountHeldEvent event) {
        String detail = "user=" + event.username() + " until=" + event.expiresAt() + " reason=" + event.reason();
        return event.correlationId() == null ? detail : detail + " correlation=" + event.correlationId();
    }
}

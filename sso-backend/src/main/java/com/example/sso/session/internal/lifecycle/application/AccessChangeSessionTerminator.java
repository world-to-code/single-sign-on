package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.organization.OrganizationAccessRevokedEvent;
import com.example.sso.user.account.UserAccessChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Ends a user's live sessions when their access changes, so a frozen {@code SecurityContext} can't keep acting
 * on stale authority until idle/absolute expiry. Both listeners run {@code AFTER_COMMIT} (a rolled-back mutation
 * never terminates sessions; {@code fallbackExecution} covers publishers outside a transaction) and delegate to
 * {@link ResilientSessionTermination}, which retries a transient store blip and, if that is exhausted, hands the
 * termination to the durable sweep — so an {@code AFTER_COMMIT} exception (otherwise swallowed) never leaves a
 * session alive on stale authority. Kept separate from {@code SessionManagerImpl} so the terminator depends on
 * the resilient wrapper while the wrapper's re-driver depends on the session manager, with no dependency cycle.
 */
@Component
class AccessChangeSessionTerminator {

    private final ResilientSessionTermination termination;

    AccessChangeSessionTerminator(ResilientSessionTermination termination) {
        this.termination = termination;
    }

    /** A user was disabled/deleted/re-roled: end their sessions in their own org (the event carries username). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserAccessChanged(UserAccessChangedEvent event) {
        termination.run(SessionTerminationRequest.forUser(event.username(), event.orgId()));
    }

    /**
     * A user's membership in an org was revoked (or the org was suspended, which fans this out per member). End
     * that user's live sessions bound to THAT org only — a session logged into another org they still belong to
     * must survive. The username is resolved from the user id when the termination is driven.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrganizationAccessRevoked(OrganizationAccessRevokedEvent event) {
        termination.run(SessionTerminationRequest.forMember(event.userId(), event.orgId()));
    }
}

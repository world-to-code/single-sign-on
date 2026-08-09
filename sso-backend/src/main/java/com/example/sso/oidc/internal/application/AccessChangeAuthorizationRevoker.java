package com.example.sso.oidc.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.oidc.OidcAuthorizations;
import com.example.sso.user.account.UserAccessChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The other half of revocation: when a user's access changes, take away the grants their sessions handed to
 * applications, not just the sessions.
 *
 * <p>A sibling of the session module's terminator rather than a step inside it, so neither module has to know
 * the other exists — both simply answer the same fact. It runs {@code AFTER_COMMIT} for the same reason: a
 * mutation that rolled back revoked nothing, and {@code fallbackExecution} covers publishers outside a
 * transaction.
 *
 * <p>A failure here is AUDITED rather than logged and forgotten. An exception thrown from an
 * {@code AFTER_COMMIT} listener is swallowed by the framework, and what it would leave behind is precisely
 * the state this class exists to prevent — a revoked account whose relying parties keep refreshing. Unlike
 * session termination this needs no durable retry: it is a delete in the same database whose commit has just
 * succeeded, so the failure modes that motivated the sweep there do not apply.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AccessChangeAuthorizationRevoker {

    private final OidcAuthorizations authorizations;
    private final AuditService audit;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserAccessChanged(UserAccessChangedEvent event) {
        try {
            int revoked = authorizations.revokeForUser(event.username(), event.orgId());
            if (revoked > 0) {
                // Only when something actually went: most access changes touch a user holding no grant at
                // all, and a row per no-op would bury the ones that mattered.
                audit.record(new AuditRecord(AuditType.OIDC_AUTHORIZATION_REVOKED, event.username(), true,
                        "authorizations=" + revoked, null, event.orgId()));
            }
        } catch (RuntimeException e) {
            log.error("Failed to revoke OAuth2 authorizations for a user whose access changed", e);
            audit.record(new AuditRecord(AuditType.OIDC_AUTHORIZATION_REVOKED, event.username(), false,
                    "the applications this account is signed in to can keep refreshing their tokens",
                    null, event.orgId()));
        }
    }
}

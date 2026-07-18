package com.example.sso.oidc.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.logoutretry.LogoutRetryCoordinator;
import com.example.sso.oidc.OidcBackchannelSessionIndex;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Looks up the clients that hold a token for the terminated session ({@code sid}) and delivers each a signed
 * {@code logout_token} via {@link OidcBackchannelDelivery}. Per-client failures are isolated and audited so
 * one unreachable RP never blocks the others; only settled clients are cleared, so a transiently-failed one
 * stays for the durable retry sweep.
 */
@Service
class LogoutPropagationImpl implements LogoutPropagation {

    private static final Logger log = LoggerFactory.getLogger(LogoutPropagationImpl.class);

    private final OidcBackchannelSessionIndex index;
    private final AuditService audit;
    private final LogoutRetryCoordinator retryCoordinator;
    private final OidcBackchannelDelivery delivery;

    LogoutPropagationImpl(OidcBackchannelSessionIndex index, AuditService audit,
            LogoutRetryCoordinator retryCoordinator, OidcBackchannelDelivery delivery) {
        this.index = index;
        this.audit = audit;
        this.retryCoordinator = retryCoordinator;
        this.delivery = delivery;
    }

    // @Async on the DEDICATED bounded `logoutPropagationExecutor` (not the shared onboarding pool): the
    // (blocking, per-RP up to `timeout`) fan-out runs off the Spring Session Redis message-listener thread that
    // fires SessionDestroyedEvent — a slow/hung RP no longer serializes every other session's termination
    // propagation. A void @Async failure is surfaced by LoggingAsyncUncaughtExceptionHandler.
    @Override
    @Async("logoutPropagationExecutor")
    public void propagate(String sid, String username) {
        OidcBackchannelSessionIndex.Participants participants = index.lookup(sid);
        String subject = participants.username() != null ? participants.username() : username;
        Set<String> settled = new HashSet<>();
        for (String clientId : participants.clientIds()) {
            BackchannelDeliveryOutcome outcome;
            try {
                outcome = delivery.deliver(clientId, subject, sid);
                audit.record(new AuditRecord(AuditType.OIDC_BACKCHANNEL_LOGOUT,
                        subject, outcome == BackchannelDeliveryOutcome.DELIVERED, "client=" + clientId, null));
            } catch (RuntimeException e) {
                // An IdP-side infra fault (client-org lookup, issuer resolution, audit write, a DB blip mid-fan-out)
                // must NOT drop this client's logout: treat it as TRANSIENT so it stays in the index and the sweep
                // re-drives it, and so the loop can never abort before the reschedule below (which alone makes the
                // termination durable). Never lose a revocation to a transient fault.
                log.warn("back-channel logout for client {} failed to process: {}", clientId, e.getMessage());
                outcome = BackchannelDeliveryOutcome.TRANSIENT;
            }
            if (outcome != BackchannelDeliveryOutcome.TRANSIENT) {
                settled.add(clientId); // delivered or terminally undeliverable — never retry it
            }
        }
        // Clear ONLY what is settled; a transiently-failed client stays in the index for the durable retry
        // sweep, so a temporarily-unreachable RP no longer loses this logout (zero-trust: revocation propagates).
        int remaining = index.removeParticipants(sid, settled);
        retryCoordinator.reschedule(OidcLogoutRetryDriver.RETRY_QUEUE, sid, subject, remaining > 0,
                () -> auditGaveUp(sid, subject));
    }

    // Called once the retry cap is exhausted: the clients still in the index were never delivered. Audit each
    // abandonment so a logout that could not propagate is VISIBLE to operators (A09), never silent, then clear.
    private void auditGaveUp(String sid, String subject) {
        for (String clientId : index.lookup(sid).clientIds()) {
            audit.record(new AuditRecord(AuditType.OIDC_BACKCHANNEL_LOGOUT, subject, false,
                    "client=" + clientId + " abandoned after exhausting retries", null));
        }
        index.clear(sid);
    }
}

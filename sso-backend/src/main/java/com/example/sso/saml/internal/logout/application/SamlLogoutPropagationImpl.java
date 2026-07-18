package com.example.sso.saml.internal.logout.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.logoutretry.LogoutRetryCoordinator;
import com.example.sso.saml.relyingparty.SloBinding;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingPartyRepository;
import com.example.sso.tenancy.OrgContext;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Back-channel SAML SLO: for each SOAP-binding participant SP of the terminated session, delivers a signed
 * {@code LogoutRequest} via {@link SamlParticipantDelivery}. Per-SP failures are isolated + audited; only
 * settled SPs are cleared, so a transiently-failed SOAP SP stays for the durable retry sweep.
 */
@Service
class SamlLogoutPropagationImpl implements SamlLogoutPropagation {

    private static final Logger log = LoggerFactory.getLogger(SamlLogoutPropagationImpl.class);

    private final SamlSloSessionIndex index;
    private final SamlRelyingPartyRepository relyingParties;
    private final AuditService audit;
    private final OrgContext orgContext;
    private final LogoutRetryCoordinator retryCoordinator;
    private final SamlParticipantDelivery delivery;

    SamlLogoutPropagationImpl(SamlSloSessionIndex index, SamlRelyingPartyRepository relyingParties,
            AuditService audit, OrgContext orgContext, LogoutRetryCoordinator retryCoordinator,
            SamlParticipantDelivery delivery) {
        this.index = index;
        this.relyingParties = relyingParties;
        this.audit = audit;
        this.orgContext = orgContext;
        this.retryCoordinator = retryCoordinator;
        this.delivery = delivery;
    }

    // NOT @Transactional: this runs on the browser-less expiry path with no OrgContext bound, and each RP is
    // resolved/signed under its OWN tenant scope below — a method-level tx would pin the first connection's
    // RLS GUC and hide every org-scoped RP (they resolve only in platform or their own org's context).
    // @Async on the DEDICATED bounded `logoutPropagationExecutor` (not the shared onboarding pool): offload the
    // blocking SOAP fan-out off the Redis message-listener thread that fires the destroy event, so a slow SP no
    // longer serializes other sessions' propagation (LoggingAsyncUncaughtExceptionHandler surfaces a void
    // failure); it also decouples this listener from the sibling OIDC one on the same event.
    @Override
    @Async("logoutPropagationExecutor")
    public void propagate(String sid, String username) {
        Map<String, String> participants = index.lookup(sid);
        Set<String> settled = new HashSet<>();
        for (Map.Entry<String, String> participant : participants.entrySet()) {
            String entityId = participant.getKey();
            if (settleParticipant(sid, entityId, participant.getValue(), username)) {
                settled.add(entityId);
            }
        }
        // Clear ONLY settled SPs; a transiently-failed SOAP SP stays in the index for the durable retry sweep,
        // so a temporarily-unreachable SP no longer loses this logout (zero-trust: revocation propagates).
        int remaining = index.removeParticipants(sid, settled);
        retryCoordinator.reschedule(SamlLogoutRetryDriver.RETRY_QUEUE, sid, username, remaining > 0,
                () -> auditGaveUp(sid, username));
    }

    // Resolve, classify and (for SOAP) deliver ONE SP. Returns true if it is settled (delivered, or terminally
    // undeliverable — RP gone / no SLO endpoint / a front-channel binding the browser-less path can never reach),
    // false to keep it for the durable retry sweep. Any IdP-side infra fault (RP lookup, audit write, a DB blip
    // mid-fan-out) is caught here and treated as TRANSIENT — so it stays in the index and the loop never aborts
    // before the reschedule below, which alone makes the termination durable. Never lose a revocation.
    private boolean settleParticipant(String sid, String entityId, String nameId, String username) {
        try {
            // Resolve cross-tenant (platform context) so an org-scoped RP is visible here, where nothing is
            // bound — RLS would otherwise hide it and the SP session would silently survive this termination.
            SamlRelyingParty rp = orgContext.callAsPlatform(
                    () -> relyingParties.findByEntityId(entityId).orElse(null));
            if (rp == null || !StringUtils.hasText(rp.getSingleLogoutUrl())) {
                return true; // SP not configured for SLO — terminal, nothing to retry to
            }
            // This event-driven path reaches only back-channel SOAP SPs. A front-channel SP is logged out by
            // the interactive redirect chain (SamlFrontChannelLogout, which audits it) when the logout is
            // browser-initiated; otherwise it persists until its own timeout. Record that this path did NOT
            // deliver it — without falsely asserting a gap, since the chain may have. TERMINAL either way: a
            // sweep re-drive can never reach a front-channel SP, so it must never be kept for retry.
            if (rp.sloBinding() != SloBinding.SOAP) {
                audit.record(new AuditRecord(AuditType.SAML_SLO, username, false,
                        "sp=" + entityId + " not reachable on the back-channel path (front-channel binding)", null));
                return true;
            }
            // A delivered SP is settled; a transient build/send failure is left in the index for the retry sweep.
            boolean delivered;
            try {
                delivered = delivery.sendSoap(rp, nameId, sid);
            } catch (RuntimeException e) {
                log.warn("SAML SLO to {} failed to build/send: {}", entityId, e.getMessage());
                delivered = false;
            }
            audit.record(new AuditRecord(AuditType.SAML_SLO, username, delivered, "sp=" + entityId, null));
            return delivered;
        } catch (RuntimeException e) {
            log.warn("SAML SLO for {} failed to process: {}", entityId, e.getMessage());
            return false; // transient infra fault — keep for the durable retry sweep
        }
    }

    // Called once the retry cap is exhausted: the SPs still in the index were never delivered. Audit each
    // abandonment so a logout that could not propagate is VISIBLE to operators (A09), never silent, then clear.
    private void auditGaveUp(String sid, String username) {
        for (String entityId : index.lookup(sid).keySet()) {
            audit.record(new AuditRecord(AuditType.SAML_SLO, username, false,
                    "sp=" + entityId + " abandoned after exhausting retries", null));
        }
        index.clear(sid);
    }
}

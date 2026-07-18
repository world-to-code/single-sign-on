package com.example.sso.onboarding.internal.application;

import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The async worker that fulfils an onboarding request off the request thread. Runs AFTER the accepting
 * transaction commits (so a rolled-back {@code start} never provisions) and on the {@code @Async} executor
 * (so the create call returns immediately). Drives the transactional steps on {@link OnboardingServiceImpl}
 * (a separate bean, so each step is its own transaction) and records FAILED if any step throws — the admin
 * is provisioned atomically inside {@code provision}, so a failure leaves no half-built tenant.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class OnboardingProvisioner {

    private final OnboardingServiceImpl service;
    private final OnboardingEmailSender email;
    private final OrgContext orgContext;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(OnboardingRequested event) {
        UUID id = event.onboardingId();
        OnboardingServiceImpl.ProvisionResult result;
        try {
            service.markProvisioning(id);
            result = service.provision(id, event.spec()); // atomic: a failure here rolls back the whole tenant
        } catch (Exception e) {
            // Nothing was created (single tx rolled back) — a stable reason, not the raw exception (A09).
            log.warn("onboarding {} provisioning failed: {}", id, e.getMessage());
            service.markFailed(id, "provisioning failed");
            return;
        }
        // The tenant is now provisioned (committed). Emailing the invite is a best-effort follow-up: a
        // failure here must NOT mark the whole job FAILED (the admin exists) — it needs a fresh invitation.
        try {
            // Send under the NEW tenant's context so its own relay is used (falls back to the platform default
            // when it has none — a brand-new tenant hasn't configured SMTP yet). NOT the inviting admin's org.
            orgContext.runInOrg(result.orgId(),
                    () -> email.sendInvitation(result.adminEmail(), result.rawToken(), result.slug()));
            service.markInvited(id);
        } catch (Exception e) {
            log.warn("onboarding {} invitation email failed: {}", id, e.getMessage());
            service.markInviteFailed(id);
        }
    }

    /**
     * Admin re-invite: mint a fresh invitation (superseding the failed/expired one) and re-send the email —
     * same best-effort semantics as the initial invite (INVITED on success, INVITE_FAILED if the mail fails).
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReinviteRequested(ReinviteRequested event) {
        UUID id = event.onboardingId();
        OnboardingServiceImpl.ReinviteResult result;
        try {
            result = service.reissueInvitation(id);
        } catch (Exception e) {
            log.warn("onboarding {} re-invite could not issue a token: {}", id, e.getMessage());
            service.markInviteFailed(id);
            return;
        }
        try {
            orgContext.runInOrg(result.orgId(),
                    () -> email.sendInvitation(result.adminEmail(), result.rawToken(), result.slug()));
            service.markInvited(id);
        } catch (Exception e) {
            log.warn("onboarding {} re-invitation email failed: {}", id, e.getMessage());
            service.markInviteFailed(id);
        }
    }
}

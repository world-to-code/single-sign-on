package com.example.sso.auth.internal.verification.application;

import com.example.sso.mfa.EmailOwnershipProof;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.EmailVerificationRequiredEvent;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mails the proof-of-ownership code an account needs before the EMAIL one-time-code factor will touch it.
 *
 * <p>Lives here rather than in {@code user} because the proof store belongs to {@code mfa}, which already
 * depends on {@code user} — calling it directly would close a module cycle. Runs AFTER_COMMIT so no mail goes
 * out for an account whose creation then rolled back, and re-binds the tenant explicitly because the listener
 * runs outside the publisher's org context.
 */
@Component
@RequiredArgsConstructor
public class EmailVerificationRequestListener {

    private final EmailOwnershipProof proofs;
    private final OrgContext orgContext;
    private final UserService users;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailVerificationRequired(EmailVerificationRequiredEvent event) {
        orgContext.runInOrg(event.orgId(), () -> {
            // Re-read rather than trust the event: the same transaction may have gone on to verify the address
            // (the seeder does exactly this for its admin), and mailing a challenge for an address that is
            // already proven is noise an operator would reasonably read as a security event.
            if (users.findById(event.userId()).filter(UserAccount::isEmailVerified).isPresent()) {
                return;
            }
            proofs.challenge(event.userId(), event.orgId(), event.email());
        });
    }
}

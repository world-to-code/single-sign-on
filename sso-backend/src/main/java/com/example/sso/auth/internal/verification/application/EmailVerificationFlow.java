package com.example.sso.auth.internal.verification.application;

import com.example.sso.auth.internal.factor.application.EmailFactorHandler;
import com.example.sso.auth.internal.login.application.CurrentUserProvider;

import com.example.sso.mfa.EmailOwnershipProof;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lets a signed-in user re-prove their own email address. An address an admin changed is unproven, which
 * disables the EMAIL one-time-code factor for it ({@code EmailFactorHandler}); this is how the user gets it
 * back — by demonstrating control of the mailbox, not by asking.
 *
 * <p>The verified flag is flipped ONLY on a redeemed proof: a flow that marked it on the request would hand
 * the factor straight back to whoever the address now points at.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationFlow {

    private final CurrentUserProvider currentUser;
    private final EmailOwnershipProof proofs;
    private final UserService users;

    /**
     * Mails a one-time code to the user's current address. An already-verified address is accepted and does
     * nothing: this endpoint is reachable by an identify-first principal, so a distinguishable response would
     * disclose whether a known address is verified.
     */
    public void request() {
        UserAccount user = currentUser.require();
        if (user.isEmailVerified()) {
            return;
        }
        proofs.challenge(user.getId(), user.getEmail());
    }

    /** Redeems the code and marks the address verified; a wrong/expired code is a 400. */
    public void confirm(String code) {
        UserAccount user = currentUser.require();
        if (!proofs.redeem(user.getId(), user.getEmail(), code)) {
            throw BadRequestException.of("auth.verification.codeInvalid");
        }
        users.markEmailVerified(user.getId());
    }
}

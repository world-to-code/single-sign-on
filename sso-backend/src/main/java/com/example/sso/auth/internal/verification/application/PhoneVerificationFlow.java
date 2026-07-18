package com.example.sso.auth.internal.verification.application;

import com.example.sso.auth.internal.login.application.CurrentUserProvider;
import com.example.sso.mfa.PhoneOwnershipProof;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Self-service phone enrollment for the SMS one-time-code factor. A signed-in user records a number, proves
 * control of it by redeeming a texted code, and can remove it. Unlike email — which verifies the address
 * already on file — enrollment supplies a NEW number, so there is no enumeration surface: it is the user
 * acting on their own account.
 *
 * <p>The verified flag is flipped ONLY on a redeemed proof: recording the number does not trust it, so the
 * SMS factor is never offered for a number the user never demonstrably controlled.
 */
@Service
@RequiredArgsConstructor
public class PhoneVerificationFlow {

    private final CurrentUserProvider currentUser;
    private final PhoneOwnershipProof proofs;
    private final UserService users;

    /**
     * Records {@code phoneNumber} (unverified) and texts a one-time code to it. Requires a fully-signed-in
     * session — enrolling a factor is a security-sensitive self-service action, not an identify-first one.
     */
    public void request(String phoneNumber) {
        UserAccount user = currentUser.requireMfaComplete();
        users.enrollPhone(user.getId(), phoneNumber);
        proofs.challenge(user.getId(), user.getOrgId(), phoneNumber);
    }

    /** Redeems the code and marks the number verified; a wrong/expired code is a 400. */
    public void confirm(String code) {
        UserAccount user = currentUser.requireMfaComplete();
        if (user.getPhoneNumber() == null || !proofs.redeem(user.getId(), user.getPhoneNumber(), code)) {
            throw BadRequestException.of("auth.verification.codeInvalid");
        }
        users.markPhoneVerified(user.getId(), user.getPhoneNumber());
    }

    /** Removes the number and its proof, disabling the SMS factor for the user. */
    public void remove() {
        UserAccount user = currentUser.requireMfaComplete();
        users.removePhone(user.getId());
    }
}

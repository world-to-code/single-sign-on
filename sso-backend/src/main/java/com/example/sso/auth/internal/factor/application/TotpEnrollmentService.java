package com.example.sso.auth.internal.factor.application;

import com.example.sso.auth.internal.login.application.CurrentUserProvider;

import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.mfa.MfaService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.user.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Self-service TOTP authenticator enrollment for an already-signed-in user (from "My Profile"). */
@Service
@RequiredArgsConstructor
public class TotpEnrollmentService {

    private final CurrentUserProvider currentUser;
    private final FactorHandlers factorHandlers;
    private final MfaService mfaService;
    private final AuditService audit;

    /** Starts TOTP setup: returns the secret + scannable QR (stored pending in session); 409 if already set up. */
    public FactorChallenge setup(HttpServletRequest request) {
        UserAccount user = currentUser.requireMfaComplete();
        if (factorHandlers.isEnrolled(AuthFactor.TOTP, user)) {
            throw ConflictException.of("auth.totp.alreadyEnrolled");
        }

        return factorHandlers.get(AuthFactor.TOTP).prepare(user, request);
    }

    /** Confirms setup by verifying a code against the freshly scanned secret; persists on success, 400 otherwise. */
    public void confirmSetup(FactorVerificationRequest verification, HttpServletRequest request) {
        UserAccount user = currentUser.requireMfaComplete();
        if (!factorHandlers.get(AuthFactor.TOTP).verify(user, verification, request)) {
            throw BadRequestException.of("auth.code.incorrect");
        }

        audit.record(AuditType.TOTP_ENROLLED, user.getUsername(), true);
    }

    /** Removes the signed-in user's TOTP authenticator (so it can be re-enrolled). */
    public void disable() {
        UserAccount user = currentUser.requireMfaComplete();
        mfaService.resetMfa(user.getId());
        audit.record(AuditType.TOTP_REMOVED, user.getUsername(), true);
    }
}

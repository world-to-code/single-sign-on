package com.example.sso.auth.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.mfa.MfaService;
import com.example.sso.mfa.QrCodeService;
import com.example.sso.mfa.TotpEnrollment;
import com.example.sso.user.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/** TOTP factor: enrollment (QR) on first use, code challenge thereafter. */
@Component
public class TotpFactorHandler implements FactorHandler {

    private static final String PENDING_SECRET = "TOTP_PENDING_SECRET";

    private final MfaService mfa;
    private final QrCodeService qrCodes;

    public TotpFactorHandler(MfaService mfa, QrCodeService qrCodes) {
        this.mfa = mfa;
        this.qrCodes = qrCodes;
    }

    @Override
    public AuthFactor factor() {
        return AuthFactor.TOTP;
    }

    @Override
    public boolean isEnrolled(UserAccount user) {
        return mfa.hasEnabledTotp(user.getId());
    }

    @Override
    public boolean enrollableAtLogin() {
        return true;
    }

    @Override
    public FactorChallenge prepare(UserAccount user, HttpServletRequest request) {
        if (mfa.hasEnabledTotp(user.getId())) {
            return FactorChallenge.none(); // already enrolled -> a code challenge needs no QR
        }

        HttpSession session = request.getSession(true);
        String pending = (String) session.getAttribute(PENDING_SECRET);
        TotpEnrollment enrollment = pending != null ? mfa.enrollmentFor(user, pending) : mfa.newEnrollment(user);
        session.setAttribute(PENDING_SECRET, enrollment.secret());
        return FactorChallenge.enrollment(enrollment.secret(), qrCodes.pngDataUri(enrollment.otpauthUri()));
    }

    @Override
    public boolean verify(UserAccount user, FactorVerificationRequest verification, HttpServletRequest request) {
        if (verification.code() == null) {
            return false;
        }

        if (mfa.hasEnabledTotp(user.getId())) {
            return mfa.verifyTotp(user.getId(), verification.code());
        }

        HttpSession session = request.getSession(false);
        String secret = session == null ? null : (String) session.getAttribute(PENDING_SECRET);
        boolean confirmed = mfa.confirmEnrollment(user, secret, verification.code());
        if (confirmed && session != null) {
            session.removeAttribute(PENDING_SECRET);
        }
        return confirmed;
    }
}

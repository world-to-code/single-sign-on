package com.example.sso.mfa;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.user.AppUser;
import com.example.sso.user.MfaFactor;
import com.example.sso.user.MfaFactorRepository;
import com.example.sso.user.MfaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * TOTP enrollment and verification, persisted via {@link MfaFactor}. A factor stays
 * {@code enabled = false} until the user proves a valid code (confirming enrollment).
 */
@Service
public class MfaService {

    private final MfaFactorRepository factors;
    private final TotpService totpService;
    private final SecretCipher secretCipher;
    private final String issuerName;

    public MfaService(MfaFactorRepository factors,
                      TotpService totpService,
                      SecretCipher secretCipher,
                      @Value("${sso.totp.issuer-name:MiniSSO}") String issuerName) {
        this.factors = factors;
        this.totpService = totpService;
        this.secretCipher = secretCipher;
        this.issuerName = issuerName;
    }

    /**
     * Generates a fresh TOTP secret and {@code otpauth://} URI for enrollment. The secret is
     * NOT persisted here — the caller holds it in the HTTP session until {@link #confirmEnrollment}
     * verifies a code. This avoids a premature {@code mfa_factor} row (and the unique-constraint
     * race when the enrollment is started more than once, e.g. a re-rendered SPA).
     */
    public TotpEnrollment newEnrollment(AppUser user) {
        return enrollmentFor(user, totpService.generateSecret());
    }

    /** Rebuilds the enrollment (otpauth URI) for an existing, session-held pending secret. */
    public TotpEnrollment enrollmentFor(AppUser user, String secret) {
        return new TotpEnrollment(secret, totpService.provisioningUri(secret, user.getUsername(), issuerName));
    }

    /**
     * Confirms enrollment by verifying a code against the pending (session-held) secret, then
     * upserts the user's single TOTP factor (reusing any existing row) and enables it.
     */
    @Transactional
    public boolean confirmEnrollment(AppUser user, String secret, String code) {
        if (secret == null) {
            return false;
        }
        long step = totpService.matchingCounter(secret, code);
        if (step < 0) {
            return false;
        }
        MfaFactor factor = factors.findByUserIdAndType(user.getId(), MfaType.TOTP)
                .orElseGet(() -> new MfaFactor(user, MfaType.TOTP, "Authenticator app"));
        factor.assignSecret(secretCipher.encrypt(secret)); // encrypted at rest
        factor.recordUsedStep(step); // the enrollment code itself can't be replayed to log in
        factor.enable();
        factors.save(factor);
        return true;
    }

    /**
     * Verifies a TOTP code at challenge time against the user's enabled factor, rejecting replays:
     * a code whose time-step was already consumed (or is older) is refused (RFC 6238 §5.2).
     */
    @Transactional
    public boolean verifyTotp(UUID userId, String code) {
        MfaFactor factor = factors.findByUserIdAndType(userId, MfaType.TOTP)
                .filter(MfaFactor::isEnabled).orElse(null);
        if (factor == null) {
            return false;
        }
        long step = totpService.matchingCounter(secretCipher.decrypt(factor.getSecret()), code);
        if (step < 0) {
            return false;
        }
        if (factor.getLastUsedStep() != null && step <= factor.getLastUsedStep()) {
            return false; // already used this (or a later) code — replay
        }
        factor.recordUsedStep(step);
        factors.save(factor);
        return true;
    }

    @Transactional(readOnly = true)
    public boolean hasEnabledTotp(UUID userId) {
        return factors.existsByUserIdAndTypeAndEnabledTrue(userId, MfaType.TOTP);
    }

    /** Removes all MFA factors for a user so they must re-enroll (admin recovery). */
    @Transactional
    public void resetMfa(UUID userId) {
        factors.deleteByUserId(userId);
    }
}

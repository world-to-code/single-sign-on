package com.example.sso.mfa.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.mfa.MfaService;
import com.example.sso.mfa.TotpEnrollment;
import com.example.sso.mfa.internal.domain.MfaFactor;
import com.example.sso.mfa.internal.domain.MfaFactorRepository;
import com.example.sso.mfa.internal.domain.MfaType;
import com.example.sso.user.account.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Default {@link MfaService}. TOTP enrollment and verification, persisted via {@link MfaFactor}. A
 * factor stays {@code enabled = false} until the user proves a valid code (confirming enrollment).
 */
@Service
public class MfaServiceImpl implements MfaService {

    private final MfaFactorRepository factors;
    private final TotpService totpService;
    private final SecretCipher secretCipher;
    private final String issuerName;

    public MfaServiceImpl(MfaFactorRepository factors,
                          TotpService totpService,
                          SecretCipher secretCipher,
                          @Value("${sso.totp.issuer-name:MiniSSO}") String issuerName) {
        this.factors = factors;
        this.totpService = totpService;
        this.secretCipher = secretCipher;
        this.issuerName = issuerName;
    }

    @Override
    public TotpEnrollment newEnrollment(UserAccount user) {
        return enrollmentFor(user, totpService.generateSecret());
    }

    @Override
    public TotpEnrollment enrollmentFor(UserAccount user, String secret) {
        return new TotpEnrollment(secret, totpService.provisioningUri(secret, user.getUsername(), issuerName));
    }

    @Override
    @Transactional
    public boolean confirmEnrollment(UserAccount user, String secret, String code) {
        if (secret == null) {
            return false;
        }

        long step = totpService.matchingCounter(secret, code);
        if (step < 0) {
            return false;
        }

        MfaFactor factor = factors.findByUserIdAndType(user.getId(), MfaType.TOTP)
                .orElseGet(() -> new MfaFactor(user.getId(), MfaType.TOTP, "Authenticator app"));
        factor.assignSecret(secretCipher.encrypt(secret)); // encrypted at rest
        factor.recordUsedStep(step); // the enrollment code itself can't be replayed to log in
        factor.enable();
        factors.save(factor);

        return true;
    }

    @Override
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

    @Override
    @Transactional(readOnly = true)
    public boolean hasEnabledTotp(UUID userId) {
        return factors.existsByUserIdAndTypeAndEnabledTrue(userId, MfaType.TOTP);
    }

    @Override
    @Transactional
    public void resetMfa(UUID userId) {
        factors.deleteByUserId(userId);
    }
}

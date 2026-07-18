package com.example.sso.mfa;

import java.util.UUID;

/**
 * MFA module's contract for generating and texting a short numeric code (the SMS factor / phone-ownership
 * proof). The implementation stays module-internal. Mirrors {@link EmailVerificationService}.
 */
public interface SmsVerificationService {

    String generateCode();

    /**
     * Texts {@code code} to {@code phoneNumber} for the tenant {@code orgId}. Runs off the request thread so a
     * code send is never measurably slower than a no-op (no phone-enrollment-status timing oracle).
     */
    void sendCode(UUID orgId, String phoneNumber, String code);
}

package com.example.sso.mfa;

/**
 * MFA module's public contract for generating and emailing a short numeric code (email factor /
 * first-login verification). The implementation stays module-internal.
 */
public interface EmailVerificationService {

    String generateCode();

    void sendCode(String email, String code);
}

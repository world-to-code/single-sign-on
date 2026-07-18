package com.example.sso.mfa;

import java.util.UUID;

/**
 * MFA module's public contract for generating and emailing a short numeric code (email factor /
 * first-login verification). The implementation stays module-internal.
 */
public interface EmailVerificationService {

    String generateCode();

    /**
     * Mails {@code code} to {@code email}. {@code orgId} is the tenant the code is FOR — the mail is routed
     * through that org's own SMTP relay (or the platform default when {@code orgId} is null, e.g. a global
     * user). The send runs off the request thread, so the caller passes the org explicitly rather than
     * relying on the ambient (thread-local) context following the async hop.
     */
    void sendCode(UUID orgId, String email, String code);
}

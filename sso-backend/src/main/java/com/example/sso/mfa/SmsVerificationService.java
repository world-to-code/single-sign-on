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
     *
     * @param deliveryKey identifies this login attempt, so a send that fails AFTER the response has gone can
     *                    still be reported on the person's next request. Null to record nothing.
     */
    void sendCode(UUID orgId, String phoneNumber, String code, String deliveryKey);

    /** Forgets any earlier failure for {@code deliveryKey}. Call before asking for a fresh code. */
    void clearDeliveryFailure(String deliveryKey);

    /**
     * Whether the code most recently sent for {@code deliveryKey} failed to leave. Lets the caller say "we
     * could not send it" instead of "that code is wrong", which is the only honest answer when no code exists.
     */
    boolean deliveryFailed(String deliveryKey);
}

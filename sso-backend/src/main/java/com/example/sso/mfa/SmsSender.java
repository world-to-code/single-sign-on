package com.example.sso.mfa;

import java.util.UUID;

/**
 * Sends a text message to a phone number, on behalf of an org (kept for a future per-tenant SMS route; the
 * current platform sender ignores it). The MFA module's outbound-SMS seam — the analogue of the email
 * {@code TenantMailSender}. The default implementation is a DEV logging stub; a real deployment replaces the
 * bean with an SMS gateway (Twilio, etc.).
 */
public interface SmsSender {

    void send(UUID orgId, String phoneNumber, String message);
}

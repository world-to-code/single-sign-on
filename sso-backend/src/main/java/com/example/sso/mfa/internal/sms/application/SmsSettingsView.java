package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;
import com.example.sso.mfa.internal.sms.domain.SmsSettings;

/**
 * The acting tier's own SMS configuration as the console reads it. {@code configured} false = the tier
 * inherits.
 *
 * <p>There is no secret field, and that is the point rather than an omission: the API secret is write-only, so
 * a console that has never received it cannot leak it back, and an administrator editing the sender number
 * submits a blank secret and keeps the stored one.
 */
public record SmsSettingsView(boolean configured, SmsProvider provider, String apiKey, String senderNumber) {

    static SmsSettingsView of(SmsSettings row) {
        return new SmsSettingsView(true, row.getProvider(), row.getApiKey(), row.getSenderNumber());
    }

    static SmsSettingsView notConfigured() {
        return new SmsSettingsView(false, null, null, null);
    }
}

package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;

/** What the settings page submits. A blank {@code apiSecret} on an update keeps the stored one. */
public record SmsSettingsSpec(SmsProvider provider, String apiKey, String apiSecret, String senderNumber) {
}

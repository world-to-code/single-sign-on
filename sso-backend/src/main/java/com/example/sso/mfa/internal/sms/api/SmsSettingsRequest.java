package com.example.sso.mfa.internal.sms.api;

import com.example.sso.mfa.SmsProvider;
import com.example.sso.mfa.internal.sms.application.SmsSettingsSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The SMS gateway an administrator is registering for their tier.
 *
 * @param apiSecret write-only, and OPTIONAL on an update: blank keeps the stored one, so editing the sender
 *                  number does not require re-entering a credential the console was never given back.
 */
public record SmsSettingsRequest(
        @NotNull SmsProvider provider,
        // Constrained to an opaque-token charset because Twilio's client interpolates the key into the request
        // PATH (it is the Account SID there). Both providers' keys are alphanumeric, so forbidding separators
        // and dots costs nothing and keeps a stored key from ever reshaping the URL it is sent to.
        @NotBlank @Size(max = 255) @Pattern(regexp = "[A-Za-z0-9_-]+") String apiKey,
        @Size(max = 255) String apiSecret,
        // Digits, optionally with the leading + of an E.164 number, and the hyphens Korean numbers are written
        // with. Anything else is a typo the provider would reject later and less clearly.
        @NotBlank @Size(max = 32) @Pattern(regexp = "\\+?[0-9-]{4,32}") String senderNumber) {

    public SmsSettingsSpec toSpec() {
        return new SmsSettingsSpec(provider, apiKey, apiSecret, senderNumber);
    }
}

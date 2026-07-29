package com.example.sso.email.internal.application;

import com.example.sso.email.EmailProvider;
import com.example.sso.email.internal.domain.SmtpSettings;

/**
 * The acting tier's mail configuration for the admin settings page — NEVER a secret, neither the SMTP password
 * nor the API key, and there is no field for either. {@code configured} is false when the tier has no own row
 * (it inherits the platform default); the other fields are then null/defaults.
 */
public record SmtpSettingsView(boolean configured, EmailProvider provider, String host, Integer port,
                               String username, String fromAddress, boolean starttls) {

    static SmtpSettingsView of(SmtpSettings settings) {
        return new SmtpSettingsView(true, settings.getProvider(), settings.getHost(), settings.getPort(),
                settings.getUsername(), settings.getFromAddress(), settings.isStarttls());
    }

    static SmtpSettingsView notConfigured() {
        return new SmtpSettingsView(false, EmailProvider.SMTP, null, null, null, null, true);
    }
}

package com.example.sso.email.internal.application;

import com.example.sso.email.internal.domain.SmtpSettings;

/**
 * The acting tier's SMTP config for the admin settings page — NEVER the password (write-only). {@code
 * configured} is false when the tier has no own row (it inherits the platform default); the other fields are
 * then null/defaults.
 */
public record SmtpSettingsView(boolean configured, String host, Integer port, String username,
                               String fromAddress, boolean starttls) {

    static SmtpSettingsView of(SmtpSettings settings) {
        return new SmtpSettingsView(true, settings.getHost(), settings.getPort(), settings.getUsername(),
                settings.getFromAddress(), settings.isStarttls());
    }

    static SmtpSettingsView notConfigured() {
        return new SmtpSettingsView(false, null, null, null, null, true);
    }
}

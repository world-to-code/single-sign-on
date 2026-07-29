package com.example.sso.email.internal.application;

import com.example.sso.email.EmailProvider;

/**
 * Validated write input for {@link SmtpSettingsService#update}.
 *
 * <p>Two shapes share it because they answer one question — how this tenant sends mail. An {@code SMTP} spec
 * carries the relay ({@code host}/{@code port}/credentials); an HTTP provider carries {@code apiKey} and
 * ignores them. Both secrets are PLAINTEXT here and encrypted by the service before they touch the database.
 */
public record SmtpSettingsSpec(EmailProvider provider, String host, int port, String username, String password,
                               String apiKey, String fromAddress, boolean starttls) {

    boolean isSmtp() {
        return provider == EmailProvider.SMTP;
    }
}

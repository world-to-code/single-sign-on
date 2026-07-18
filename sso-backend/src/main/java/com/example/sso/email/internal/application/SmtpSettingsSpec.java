package com.example.sso.email.internal.application;

/**
 * Validated write input for {@link SmtpSettingsService#update}. {@code password} is PLAINTEXT on the write path
 * (encrypted by the service before it touches the DB); {@code null}/blank username means an unauthenticated
 * relay (password ignored).
 */
public record SmtpSettingsSpec(String host, int port, String username, String password, String fromAddress,
                               boolean starttls) {
}

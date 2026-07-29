package com.example.sso.email.internal.application;

import com.example.sso.email.EmailProvider;

/**
 * A resolved way of sending — the DECRYPTED view of an {@code SmtpSettings} row, internal to the email module.
 * Never leaves it; the secrets live here only long enough to build one send.
 *
 * <p>Which fields carry meaning depends on {@link #provider()}: an SMTP relay uses host/port/credentials, an
 * HTTP provider uses {@link #apiKey()}. {@code fromAddress} is common to both.
 */
record MailServer(EmailProvider provider, String host, Integer port, String username, String password,
                  String apiKey, String fromAddress, boolean starttls) {

    boolean authenticated() {
        return username != null && !username.isBlank();
    }
}

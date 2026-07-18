package com.example.sso.email.internal.application;

/**
 * A resolved SMTP relay ready to send with — the DECRYPTED view of a {@code SmtpSettings} row, internal to the
 * email module (never leaves it; the password stays here only long enough to build the sender).
 */
record MailServer(String host, int port, String username, String password, String fromAddress, boolean starttls) {

    boolean authenticated() {
        return username != null && !username.isBlank();
    }
}

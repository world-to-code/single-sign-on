package com.example.sso.email.internal.application;

import org.springframework.mail.javamail.JavaMailSender;

/**
 * A resolved outbound relay: the {@link JavaMailSender} to send through and the {@code From} address to stamp
 * ({@code null} to leave the sender's default). Either the acting tenant's own relay or the platform fallback.
 */
record MailRelay(JavaMailSender sender, String fromAddress) {
}

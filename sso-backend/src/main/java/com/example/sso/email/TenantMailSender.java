package com.example.sso.email;

import org.springframework.mail.SimpleMailMessage;

/**
 * Sends an email through the ACTING tenant's own SMTP relay when it has one configured, else through the
 * platform default ({@code spring.mail.*}). The tenant is resolved from the ambient request context, so a
 * caller need only build the message; a send is never failed for want of a tenant config (transparent
 * fallback). Replaces the direct {@code JavaMailSender} injection at every email call site.
 */
public interface TenantMailSender {

    void send(SimpleMailMessage message);
}

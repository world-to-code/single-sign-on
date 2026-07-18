package com.example.sso.email;

import com.example.sso.email.template.OutboundEmail;

/**
 * Sends a rendered {@link OutboundEmail} (subject + HTML body + plain-text alternative) through the ACTING
 * tenant's own SMTP relay when it has one configured, else through the platform default ({@code spring.mail.*}).
 * The tenant is resolved from the ambient request context, so a caller need only compose the message; a send is
 * never failed for want of a tenant config (transparent fallback). The message is produced by
 * {@link com.example.sso.email.template.EmailComposer} from the tenant's template.
 */
public interface TenantMailSender {

    void send(OutboundEmail email);
}

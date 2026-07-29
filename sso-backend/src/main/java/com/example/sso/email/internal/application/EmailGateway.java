package com.example.sso.email.internal.application;

import com.example.sso.email.EmailProvider;
import com.example.sso.email.template.OutboundEmail;

/**
 * One way of getting mail out. The message is the same everywhere; the transport, the credential and the
 * request it builds are not, which is the whole reason this is a port with one implementation per
 * {@link EmailProvider} rather than a configuration value.
 */
interface EmailGateway {

    EmailProvider provider();

    /** Sends one message, or throws — the caller decides what a failure to deliver means. */
    void send(MailServer account, OutboundEmail email);
}

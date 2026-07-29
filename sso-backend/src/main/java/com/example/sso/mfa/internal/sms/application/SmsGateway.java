package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;

/**
 * One SMS provider's wire protocol. The credentials and the message are the same everywhere; the endpoint, the
 * way the request is authenticated and the body it wants are not, which is the entire reason this is a port
 * with one implementation per {@link SmsProvider} rather than a config value.
 */
interface SmsGateway {

    SmsProvider provider();

    /** Sends one message, or throws — the caller decides what a failure to deliver a one-time code means. */
    void send(SmsAccount account, String to, String message);
}

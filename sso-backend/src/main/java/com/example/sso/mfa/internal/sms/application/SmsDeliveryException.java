package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;

/**
 * A send that did not reach the recipient, in terms an operator and a retry policy can both act on.
 *
 * <p>Raised instead of letting the HTTP client's own exception escape, for two reasons. The client's message
 * embeds the provider's response body verbatim, which then lands in a log and an audit row — fine for
 * "발신번호 미등록", not fine in general for a body nobody controls. And an {@code HttpClientErrorException}
 * says nothing about whether trying again is safe, which for a per-message-billed channel is the question that
 * matters.
 *
 * @param providerCode the provider's own error code where it gave one ({@code FailedToAddMessage}), else a
 *                     synthetic one. NEVER the provider's message text — that is unbounded and untrusted.
 * @param retryable    whether the message is KNOWN not to have been accepted. False whenever that is merely
 *                     likely: a duplicate text is billed twice and arrives twice, so ambiguity means no retry.
 */
class SmsDeliveryException extends RuntimeException {

    /** The connection never opened, so nothing was sent — the one case where trying again is free of doubt. */
    static final String NOT_CONNECTED = "NotConnected";

    /** The request went out and the answer never came. It MAY have been accepted; treat as delivered-unknown. */
    static final String NO_ANSWER = "NoAnswer";

    private final transient SmsProvider provider;
    private final String providerCode;
    private final boolean retryable;

    private SmsDeliveryException(SmsProvider provider, String providerCode, boolean retryable, Throwable cause) {
        super(provider + " did not accept the message: " + providerCode, cause);
        this.provider = provider;
        this.providerCode = providerCode;
        this.retryable = retryable;
    }

    /** The provider answered and refused. Never retryable: the same request would be refused the same way. */
    static SmsDeliveryException refused(SmsProvider provider, String providerCode, Throwable cause) {
        return new SmsDeliveryException(provider, providerCode, false, cause);
    }

    /** The provider was not reached. Retryable only when the connection demonstrably never opened. */
    static SmsDeliveryException unreachable(SmsProvider provider, String code, Throwable cause) {
        return new SmsDeliveryException(provider, code, NOT_CONNECTED.equals(code), cause);
    }

    SmsProvider provider() {
        return provider;
    }

    String providerCode() {
        return providerCode;
    }

    boolean retryable() {
        return retryable;
    }
}

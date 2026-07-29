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
 *                     synthetic one. This is what the AUDIT row carries.
 * @param providerDetail the provider's own explanation, for the SERVER LOG only — kept out of the audit row and
 *                     off every response, because it is unbounded text from a third party. Kept at all because
 *                     a catch-all code like {@code FailedToAddMessage} says only that something was refused,
 *                     which cost a diagnosis the first time this fired.
 * @param retryable    whether the message is KNOWN not to have been accepted. False whenever that is merely
 *                     likely: a duplicate text is billed twice and arrives twice, so ambiguity means no retry.
 */
class SmsDeliveryException extends RuntimeException {

    /** The connection never opened, so nothing was sent — the one case where trying again is free of doubt. */
    static final String NOT_CONNECTED = "NotConnected";

    /** The request went out and the answer never came. It MAY have been accepted; treat as delivered-unknown. */
    static final String NO_ANSWER = "NoAnswer";

    /** Bounded because it is pasted from a third party's response into a log line. */
    private static final int MAX_DETAIL = 200;

    private final transient SmsProvider provider;
    private final String providerCode;
    private final String providerDetail;
    private final String sentFrom;
    private final boolean retryable;

    private SmsDeliveryException(SmsProvider provider, String sentFrom, String providerCode,
            String providerDetail, boolean retryable, Throwable cause) {
        super(provider + " did not accept the message: " + providerCode, cause);
        this.provider = provider;
        this.sentFrom = sentFrom;
        this.providerCode = providerCode;
        this.providerDetail = truncate(providerDetail);
        this.retryable = retryable;
    }

    private static String truncate(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        return detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL) + "…";
    }

    /** The provider answered and refused. Never retryable: the same request would be refused the same way. */
    static SmsDeliveryException refused(SmsProvider provider, String sentFrom, String providerCode,
            String providerDetail, Throwable cause) {
        return new SmsDeliveryException(provider, sentFrom, providerCode, providerDetail, false, cause);
    }

    /** The provider was not reached. Retryable only when the connection demonstrably never opened. */
    static SmsDeliveryException unreachable(SmsProvider provider, String sentFrom, String code, Throwable cause) {
        return new SmsDeliveryException(provider, sentFrom, code, null, NOT_CONNECTED.equals(code), cause);
    }

    SmsProvider provider() {
        return provider;
    }

    String providerCode() {
        return providerCode;
    }

    /**
     * The sending number as it went ON THE WIRE — not as it is stored.
     *
     * <p>Those are the same for a provider we send verbatim to and different for one we normalise for, and the
     * log used to print the stored value for both. That is precisely the fact in question when a provider says
     * it does not recognise the number, so reading it cost an afternoon.
     */
    String sentFrom() {
        return sentFrom;
    }

    /** The provider's explanation, for the log. Null when it gave none. Never put this in an audit row. */
    String providerDetail() {
        return providerDetail;
    }

    boolean retryable() {
        return retryable;
    }
}

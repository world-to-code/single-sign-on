package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;

/**
 * A resolved sending account: which gateway, the credential pair in PLAINTEXT, and the number to send from.
 *
 * <p>Deliberately short-lived and never persisted, logged or returned — it exists only between decrypting the
 * stored secret and signing the one request that uses it.
 */
record SmsAccount(SmsProvider provider, String apiKey, String apiSecret, String senderNumber) {
}

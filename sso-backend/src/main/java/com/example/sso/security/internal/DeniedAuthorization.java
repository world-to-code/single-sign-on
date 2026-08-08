package com.example.sso.security.internal;

/**
 * What a refused authorization was about, in the only two facts that are safe to keep.
 *
 * @param target      the protected thing — a declaring class and method, or a verb and path. Never an
 *                    argument value: for method security the protected object carries passwords, one-time
 *                    codes and tokens, and the audit trail is retained and widely readable.
 * @param failedCheck the expression that refused, when the check was an expression; null otherwise. This is
 *                    configuration the operator wrote, not user input, which is why it may be recorded.
 */
record DeniedAuthorization(String target, String failedCheck) {
}

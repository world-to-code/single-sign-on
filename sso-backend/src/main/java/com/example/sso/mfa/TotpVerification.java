package com.example.sso.mfa;

/**
 * The outcome of verifying a TOTP code.
 *
 * <p>A boolean cannot say WHY, and the difference matters to the person typing: a code that is merely
 * wrong is worth retyping, while a code that has already been spent will never be accepted no matter how
 * carefully it is entered again. Told only "incorrect", people retype the same six digits until the
 * authenticator rolls over on its own.
 */
public enum TotpVerification {

    GRANTED,

    /** Not six digits — the submission never reached the secret comparison at all. */
    MALFORMED,

    /** Six digits that matched no accepted time step. */
    INCORRECT,

    /**
     * A code from an elapsed step, or one this account has already spent. Both are the replay guard
     * refusing a counter it has moved past, and neither can be made to work by retyping.
     */
    STALE,

    /** No enabled TOTP factor on this account, so there is nothing to verify against. */
    NOT_ENROLLED;

    public boolean granted() {
        return this == GRANTED;
    }
}

package com.example.sso.auth.internal.factor.application;

/**
 * Why a factor verification did not grant.
 *
 * <p>Each constant owns the message key it resolves to, so the reason and the wording cannot drift apart
 * across the three services that throw them.
 *
 * <p><b>These are told apart only AFTER a first factor has already succeeded.</b> At that point the person
 * asking has proved they hold the password, so naming the reason discloses nothing they could not already
 * determine — whereas at the identify/password step the same specificity would be an enumeration oracle.
 * The distinction is the reason this taxonomy stops where it does; do not reuse it on the first step.
 */
public enum FactorFailure {

    /** The submitted value did not match. The default: says least, always safe. */
    INCORRECT("auth.code.incorrect"),

    /**
     * A wrong password where a password was asked for. Its own constant because every factor used to
     * answer "Incorrect code. Try again." — including the password step, where there is no code.
     */
    INCORRECT_PASSWORD("auth.password.incorrect"),

    /** Not shaped like a code at all — the wrong number of digits, or non-digits. */
    MALFORMED("auth.code.malformed"),

    /**
     * A code that was correct but is spent, or belongs to a time step already passed. Worth its own
     * wording because retyping it — the obvious response to "incorrect" — can never work.
     */
    STALE("auth.code.stale"),

    /**
     * A WebAuthn ceremony that did not complete, for any reason. Not split further: an expired challenge
     * and a failed assertion both call for exactly one thing, which is to present the passkey again.
     */
    PASSKEY("auth.passkey.failed");

    private final String messageKey;

    FactorFailure(String messageKey) {
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}

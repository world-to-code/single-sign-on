package com.example.sso.auth.internal.factor.application;

/**
 * The outcome of {@link FactorHandler#verify}: granted, or the reason it was not.
 *
 * <p>This replaced a bare {@code boolean}. Nineteen distinct refusals across the handlers collapsed into
 * one {@code false} and then into a single "Incorrect code" — including the replay guard, which knows
 * exactly why it refused and had no way to say so.
 *
 * @param failure the reason, or {@code null} when granted
 */
public record FactorVerificationResult(FactorFailure failure) {

    private static final FactorVerificationResult SUCCESS = new FactorVerificationResult(null);

    public static FactorVerificationResult success() {
        return SUCCESS;
    }

    /** The catch-all refusal, for a handler with nothing more specific to report. */
    public static FactorVerificationResult incorrect() {
        return failed(FactorFailure.INCORRECT);
    }

    public static FactorVerificationResult failed(FactorFailure failure) {
        return new FactorVerificationResult(failure);
    }

    public boolean granted() {
        return failure == null;
    }

    /** The message key to answer with. Granted has none, so callers check {@link #granted()} first. */
    public String messageKey() {
        return failure.messageKey();
    }
}

package com.example.sso.shared.error;

/** Authentication is required or has not been completed for the action (maps to HTTP 401). */
public class UnauthorizedException extends ApiException {

    /**
     * The deliberately non-revealing default. Most 401s here must not say WHICH of "no session", "wrong
     * credential" or "unknown account" happened, so they share one message rather than minting a key per
     * throw site — a per-site message is exactly the enumeration hint this is avoiding.
     */
    public UnauthorizedException() {
        this("auth.unauthorized", new Object[0]);
    }

    public UnauthorizedException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }

    private UnauthorizedException(String messageKey, Object[] args) {
        super(ErrorCode.UNAUTHORIZED, messageKey, args);
    }

    /** Localized variant: {@code messageKey} is resolved against the MessageSource at render time. */
    public static UnauthorizedException of(String messageKey, Object... args) {
        return new UnauthorizedException(messageKey, args);
    }
}

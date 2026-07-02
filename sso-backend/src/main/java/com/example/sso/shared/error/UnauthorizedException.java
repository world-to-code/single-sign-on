package com.example.sso.shared.error;

/** Authentication is required or has not been completed for the action (maps to HTTP 401). */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException() {
        this("Unauthorized");
    }

    public UnauthorizedException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }
}

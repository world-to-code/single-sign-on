package com.example.sso.shared.error;

/** The request is malformed or violates a domain rule (maps to HTTP 400). */
public class BadRequestException extends ApiException {
    public BadRequestException(String message) {
        super(ErrorCode.BAD_REQUEST, message);
    }
}

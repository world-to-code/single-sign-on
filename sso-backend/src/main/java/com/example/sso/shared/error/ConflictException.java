package com.example.sso.shared.error;

/** The request conflicts with current state, e.g. a duplicate (maps to HTTP 409). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}

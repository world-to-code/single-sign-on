package com.example.sso.auth.internal.application;

/**
 * Generic verification payload for a factor step. Each {@link FactorHandler} reads only the
 * field relevant to it (TOTP/email use {@code code}, password uses {@code password}, FIDO2 uses
 * the {@code credential} JSON from the browser).
 */
public record FactorVerificationRequest(String code, String password, String credential) {
}

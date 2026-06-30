package com.example.sso.mfa;

/** TOTP enrollment material handed to the user during setup. */
public record TotpEnrollment(String secret, String otpauthUri) {
}

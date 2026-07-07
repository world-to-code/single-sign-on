package com.example.sso.admin.internal.organization.api;

/** Toggles passwordless passkey (WebAuthn) first-factor sign-in for an organization. */
public record PasswordlessLoginRequest(boolean enabled) {
}

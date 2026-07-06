package com.example.sso.onboarding.internal.api;

import jakarta.validation.constraints.NotBlank;

/** Redeem request: the invitation token and the password the invitee chooses. */
public record SetPasswordRequest(@NotBlank String token, @NotBlank String password) {
}

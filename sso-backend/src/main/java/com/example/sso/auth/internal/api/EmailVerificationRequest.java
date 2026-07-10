package com.example.sso.auth.internal.api;

import jakarta.validation.constraints.NotBlank;

/** The one-time code mailed to the user's address, redeemed to prove they control it. */
public record EmailVerificationRequest(@NotBlank String code) {
}

package com.example.sso.auth.internal.verification.api;

import jakarta.validation.constraints.NotBlank;

/** The one-time code texted to the enrolled number, redeemed to prove the user controls it. */
public record PhoneVerificationRequest(@NotBlank String code) {
}

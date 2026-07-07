package com.example.sso.auth.internal.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The new password submitted at the first-login forced-reset step. */
public record ChangePasswordRequest(@NotBlank @Size(min = 8) String password) {
}

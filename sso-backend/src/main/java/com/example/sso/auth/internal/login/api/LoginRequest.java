package com.example.sso.auth.internal.login.api;

import jakarta.validation.constraints.NotBlank;

/** Username + password submitted to the JSON login endpoint. */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {
}

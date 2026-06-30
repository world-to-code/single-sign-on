package com.example.sso.auth;

import jakarta.validation.constraints.NotBlank;

/** Username + password submitted to the JSON login endpoint. */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {
}

package com.example.sso.auth.internal.api;

import jakarta.validation.constraints.NotBlank;

/** Identifier-first login: the user supplies their email (or username) before any factor. */
public record IdentifyRequest(@NotBlank String email) {
}

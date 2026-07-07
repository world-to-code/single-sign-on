package com.example.sso.auth.internal.api;

import jakarta.validation.constraints.NotBlank;

/** Customer-first entry: the customer (고객사 / workspace) slug the user is signing in to its console. */
public record CustomerRequest(@NotBlank String slug) {
}

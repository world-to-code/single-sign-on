package com.example.sso.auth.internal.api;

import jakarta.validation.constraints.NotBlank;

/** Tenant-first entry: the organization (tenant) slug the user is signing in to. */
public record OrganizationRequest(@NotBlank String slug) {
}

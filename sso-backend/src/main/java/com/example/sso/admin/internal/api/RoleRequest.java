package com.example.sso.admin.internal.api;

import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/** Create/update request for a role built from catalog permissions. */
public record RoleRequest(@NotBlank String name, Set<String> permissions) {
}

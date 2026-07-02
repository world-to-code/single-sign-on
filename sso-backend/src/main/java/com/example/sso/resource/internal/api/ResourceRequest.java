package com.example.sso.resource.internal.api;

import jakarta.validation.constraints.NotBlank;

/** Creates a resource ({@code typeName} resolves an existing resource type) — or renames one (name only). */
public record ResourceRequest(@NotBlank String name, String typeName) {
}

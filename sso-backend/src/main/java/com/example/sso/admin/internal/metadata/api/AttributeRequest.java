package com.example.sso.admin.internal.metadata.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Sets one metadata attribute. The key is a bounded identifier (so it can back a policy predicate later); the
 * value is free text within a length bound.
 */
public record AttributeRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*") String key,
        @NotBlank @Size(max = 255) String value) {
}

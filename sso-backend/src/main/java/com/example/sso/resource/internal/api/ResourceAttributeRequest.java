package com.example.sso.resource.internal.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Sets one metadata attribute on a resource. Key is a bounded identifier; value is bounded free text. */
public record ResourceAttributeRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*") String key,
        @NotBlank @Size(max = 255) String value) {
}

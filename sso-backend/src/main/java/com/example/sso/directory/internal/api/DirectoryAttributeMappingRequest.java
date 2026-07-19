package com.example.sso.directory.internal.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Maps one directory attribute onto one declared profile attribute. */
public record DirectoryAttributeMappingRequest(@NotBlank @Size(max = 64) String sourceAttribute,
                                               @NotBlank @Size(max = 64) String targetKey) {
}

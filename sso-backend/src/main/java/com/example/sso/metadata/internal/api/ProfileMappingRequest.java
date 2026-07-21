package com.example.sso.metadata.internal.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Points one of this profile's attributes at an attribute of another. */
public record ProfileMappingRequest(@NotBlank String sourceKey,
                                    @NotNull UUID targetProfileId,
                                    @NotBlank String targetKey) {
}

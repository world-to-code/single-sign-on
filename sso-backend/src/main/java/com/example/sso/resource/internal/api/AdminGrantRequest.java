package com.example.sso.resource.internal.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Assigns a user as a delegated administrator of the resource's subtree. */
public record AdminGrantRequest(@NotNull UUID userId) {
}

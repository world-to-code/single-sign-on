package com.example.sso.resource.internal.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Attaches an existing resource as a direct child (a DAG edge). */
public record ChildRequest(@NotNull UUID childId) {
}

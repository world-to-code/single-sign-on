package com.example.sso.admin.internal.organization.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Body for adding a user to an organization. */
public record OrgMemberRequest(@NotNull UUID userId) {
}

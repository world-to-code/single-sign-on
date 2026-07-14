package com.example.sso.admin.internal.role.api;

import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;

/** Sets the roles a role inherits to exactly this set (each contributes its permissions up into the role). */
public record RoleInheritanceRequest(@NotNull Set<UUID> inheritsFromRoleIds) {
}

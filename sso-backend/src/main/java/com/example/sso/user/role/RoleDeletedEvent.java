package com.example.sso.user.role;

import java.util.UUID;

/** Published after a role is deleted, so other modules can drop references to it. */
public record RoleDeletedEvent(UUID roleId) {
}

package com.example.sso.user;

import java.util.UUID;

/** Published after a user account is deleted, so other modules can drop references to it. */
public record UserDeletedEvent(UUID userId) {
}

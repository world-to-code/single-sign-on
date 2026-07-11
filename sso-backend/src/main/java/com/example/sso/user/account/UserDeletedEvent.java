package com.example.sso.user.account;

import java.util.UUID;

/** Published after a user account is deleted, so other modules can drop references to it. */
public record UserDeletedEvent(UUID userId) {
}

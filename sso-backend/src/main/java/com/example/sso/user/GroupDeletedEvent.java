package com.example.sso.user;

import java.util.UUID;

/** Published after a group is deleted, so other modules can drop references to it. */
public record GroupDeletedEvent(UUID groupId) {
}
